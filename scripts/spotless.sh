#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-}"
case "$mode" in
    tidy|check) ;;
    *) printf 'Usage: bash scripts/spotless.sh tidy|check\n' >&2; exit 1 ;;
esac
command -v mvn >/dev/null 2>&1 || {
    printf 'Spotless requires Maven and a JDK 21 or later.\n' >&2
    exit 1
}
command -v java >/dev/null 2>&1 || {
    printf 'Spotless requires Java from a JDK 21 or later.\n' >&2
    exit 1
}
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
# Keep the shared rules without resolving the application's private parent.
java "$root/scripts/SpotlessPom.java" "$root/pom.xml" "$temp/pom.xml"

files=()
original_contents=()
stream=false
if [[ "$mode" == tidy && -n "${FILE:-}" && "$FILE" != . ]]; then
    [[ -f "$FILE" && "$FILE" == *.java ]] || {
        printf 'FILE must name an existing Java file, or . for all Java files.\n' >&2
        exit 1
    }
    files=("$FILE")
    cp "$FILE" "$temp/Input.java"
    original_contents+=("$(cat "$temp/Input.java"; printf .)")
elif [[ "$mode" == tidy && "${FILE:-}" != . ]]; then
    [[ ! -t 0 ]] || {
        printf 'Use make tidy FILE=path/File.java, FILE=., or pipe Java source to make tidy.\n' >&2
        exit 1
    }
    cat >"$temp/Input.java"
    [[ -s "$temp/Input.java" ]] || {
        printf 'No Java input. Use FILE=path/File.java or FILE=. to format all Java files.\n' >&2
        exit 1
    }
    stream=true
else
    cd "$root"
    find . -type d \( -name target -o -name .git -o -name .local-run -o -name 'build-*' \) \
        -prune -o -type f -name '*.java' -print0 >"$temp/files"
    while IFS= read -r -d '' file; do
        files+=("$file")
        mkdir -p "$temp/$(dirname "$file")"
        cp "$file" "$temp/$file"
        original_contents+=("$(cat "$temp/$file"; printf .)")
    done <"$temp/files"
fi

# Format a snapshot: Spotless's check diagnostics truncate long violation lists.
# Comparing every result gives a complete, read-only scan and atomic tidy failures.
if ! mvn --batch-mode --no-transfer-progress -q -N -f "$temp/pom.xml" \
    spotless:apply >"$temp/maven.log" 2>&1; then
    cat "$temp/maven.log" >&2
    printf 'Spotless could not format the input; no source files were changed.\n' >&2
    exit 1
fi
if [[ "$stream" == true ]]; then
    cat "$temp/Input.java"
    exit 0
fi

# Check every destination before writing any file, preserving edits made while
# Maven was formatting the snapshot (including during dependency downloads).
if [[ "$mode" == tidy ]]; then
    for (( index=0; index<${#files[@]}; index++ )); do
        file="${files[$index]}"
        if [[ ! -f "$file" || "$(cat "$file"; printf .)" != "${original_contents[$index]}" ]]; then
            printf 'Source changed during formatting: %s; no source files were written back.\n' "$file" >&2
            exit 1
        fi
    done
fi

dirty=false
for file in "${files[@]}"; do
    if [[ "$mode" == tidy && "${FILE:-}" != . ]]; then
        formatted="$temp/Input.java"
    else
        formatted="$temp/$file"
    fi
    # The sentinel preserves trailing newlines in Bash command substitutions.
    if [[ "$(cat "$file"; printf .)" != "$(cat "$formatted"; printf .)" ]]; then
        if [[ "$mode" == check ]]; then
            printf '%s\n' "${file#./}"
            dirty=true
        else
            cat "$formatted" >"$file"
        fi
    fi
done
[[ "$dirty" == false ]] || exit 255
