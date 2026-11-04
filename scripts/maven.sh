#!/usr/bin/env bash
set -euo pipefail

if [[ "${MAKE_JACOCO_AUTO:-false}" != true ]]; then
    exec mvn "$@"
fi

version_output="$(mvn --version)"
major="$(printf '%s\n' "$version_output" |
    sed -n 's/^Java version: \([0-9][0-9]*\).*/\1/p')"
if [[ ! "$major" =~ ^[0-9]+$ ]]; then
    printf 'Cannot determine the JDK running Maven:\n%s\n' "$version_output" >&2
    exit 1
fi
if (( major < 21 )); then
    printf 'This project requires Java 21 or later; Maven is using Java %s.\n' "$major" >&2
    exit 1
fi
if (( major == 21 )); then
    printf 'Maven Java 21: using the Java-21-compatible JaCoCo default.\n' >&2
    exec mvn "$@"
fi

work_dir="$(mktemp -d)"
trap 'rm -f "$work_dir/version" "$work_dir/resolution.log"; rmdir "$work_dir"' EXIT
printf 'Maven Java %s: resolving the latest released JaCoCo plugin.\n' "$major" >&2
if ! mvn -B -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate \
    -Dartifact=org.jacoco:jacoco-maven-plugin:RELEASE \
    -Dexpression=project.version "-Doutput=$work_dir/version" \
    > "$work_dir/resolution.log" 2>&1; then
    cat "$work_dir/resolution.log" >&2
    printf 'Cannot resolve JaCoCo for Java %s; build not started.\n' "$major" >&2
    exit 1
fi
version="$(cat "$work_dir/version")"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    printf 'Invalid released JaCoCo version: %s\n' "$version" >&2
    exit 1
fi
printf 'Maven Java %s: selected JaCoCo %s.\n' "$major" "$version" >&2
mvn "$@" "-Djacoco.version=$version"
