#!/usr/bin/env python3
"""Build a deterministic, seed-free production Flyway feed archive."""

import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path


MIGRATION_NAME = re.compile(r"V(\d+(?:\.\d+)*)__([a-z0-9_]+)\.sql\Z")
ENVIRONMENTS = ("production", "staging", "cidev")


def collect(root: Path, expected_version: str) -> list[tuple[str, bytes]]:
    version_file = root / "database" / "version"
    version = version_file.read_text(encoding="utf-8").strip()
    if version != expected_version:
        raise ValueError(f"requested artifact version {expected_version} does not match database/version {version}")

    migration_dir = root / "database" / "migrations" / "schema"
    migrations = sorted(path for path in migration_dir.glob("*.sql") if path.is_file())
    if not migrations:
        raise ValueError(f"no production migrations found under {migration_dir}")
    versions = []
    for migration in migrations:
        match = MIGRATION_NAME.fullmatch(migration.name)
        if not match:
            raise ValueError(f"invalid Flyway migration name: {migration.name}")
        versions.append(tuple(int(part) for part in match.group(1).split(".")))
    if len(set(versions)) != len(versions):
        raise ValueError("production migrations contain duplicate Flyway versions")

    paths = [version_file]
    paths.extend(root / "database" / "config" / "flyway" / f"{environment}.conf"
                 for environment in ("common", *ENVIRONMENTS))
    paths.extend(migrations)
    entries = [
        (path.relative_to(root).as_posix(), path.read_bytes())
        for path in paths
    ]
    manifest = "".join(
        f"{hashlib.sha256(content).hexdigest()}  {name}\n"
        for name, content in entries
    ).encode("utf-8")
    entries.append(("database/MANIFEST.sha256", manifest))
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        entries = collect(args.project_root.resolve(), args.version)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w") as archive:
            for name, content in entries:
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, content)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
