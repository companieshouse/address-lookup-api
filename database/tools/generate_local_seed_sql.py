#!/usr/bin/env python3
"""Generate reviewed, deterministic Flyway SQL from the local CSV fixtures."""

import argparse
import csv
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATASETS = (
    ("2026.007", "gb_royalmailaddress", "add_gb_royalmailaddress_v1", 99),
    ("2026.008", "gb_builtaddress", "add_gb_builtaddress_v3", 794),
    ("2026.009", "isl_royalmailaddress", "add_isl_royalmailaddress_v1", 126),
    ("2026.010", "isl_builtaddress", "add_isl_builtaddress_v3", 111),
)
IDENTIFIER = re.compile(r"[a-z][a-z0-9_]*\Z")
NULL_TOKEN = "NULL"
ROWS_PER_INSERT = 50


def sql_literal(value: str) -> str:
    if value == NULL_TOKEN:
        return "NULL"
    if "\x00" in value:
        raise ValueError("PostgreSQL text values cannot contain NUL bytes")
    return "'" + value.replace("'", "''") + "'"


def render(version: str, dataset: str, table: str, expected_rows: int) -> str:
    source = ROOT / "database" / "test-data" / f"{dataset}.csv"
    target = ROOT / "database" / "migrations" / "local" / f"V{version}__seed_{dataset}.sql"
    with source.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.reader(stream)
        columns = next(reader)
        rows = list(reader)
    if len(rows) != expected_rows:
        raise ValueError(f"{source}: expected {expected_rows} data rows, got {len(rows)}")
    if not columns or any(not IDENTIFIER.fullmatch(column) for column in columns):
        raise ValueError(f"{source}: invalid or empty column name")
    if len(set(columns)) != len(columns):
        raise ValueError(f"{source}: duplicate column name")
    if any(len(row) != len(columns) for row in rows):
        raise ValueError(f"{source}: row width does not match CSV header")

    lines = [
        "-- Jira: ALS-13",
        f"-- Description: seed local {dataset.replace('_', ' ')} fixtures",
        "-- Author: team-atlas",
        "-- Created: 2026-10-12",
        f"-- Source mapping: 2026-{version[-3:]}:01 ({source.relative_to(ROOT)})",
        "",
        "DO $$",
        "BEGIN",
        f"    IF EXISTS (SELECT 1 FROM os_data.{table} LIMIT 1) THEN",
        f"        RAISE EXCEPTION 'Local fixture target os_data.{table} must be empty';",
        "    END IF;",
        "END",
        "$$;",
        "",
        f"INSERT INTO os_data.{table} ({', '.join(columns)})",
        "VALUES",
    ]
    for start in range(0, len(rows), ROWS_PER_INSERT):
        batch = rows[start : start + ROWS_PER_INSERT]
        for offset, row in enumerate(batch):
            suffix = "," if offset < len(batch) - 1 else ";"
            lines.append("    (" + ", ".join(map(sql_literal, row)) + ")" + suffix)
        if start + ROWS_PER_INSERT < len(rows):
            lines[-1] = lines[-1][:-1] + ","
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated SQL differs from committed files")
    args = parser.parse_args()
    failed = False
    for version, dataset, table, expected_rows in DATASETS:
        target = ROOT / "database" / "migrations" / "local" / f"V{version}__seed_{dataset}.sql"
        content = render(version, dataset, table, expected_rows)
        if args.check:
            if not target.is_file() or target.read_text(encoding="utf-8") != content:
                print(f"Generated migration is stale: {target.relative_to(ROOT)}", file=sys.stderr)
                failed = True
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("w", encoding="utf-8", newline="\n") as stream:
                stream.write(content)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
