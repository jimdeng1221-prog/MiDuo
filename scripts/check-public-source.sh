#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd -P)
allowlist="$repository_root/PUBLIC-FILES"
target=${1:-$repository_root}
if [[ ! -d "$target" ]]; then
    echo "Public source check target is not a directory: $target" >&2
    exit 1
fi
target=$(cd "$target" && pwd -P)

if [[ -n "$(find "$target" -type l -print -quit)" ]]; then
    echo "Public source contains a symlink; symlinks are not allowed." >&2
    exit 1
fi
if [[ -n "$(find "$target" ! -type d ! -type f -print -quit)" ]]; then
    echo "Public source contains a non-regular filesystem entry." >&2
    exit 1
fi

declare -a allowed_files=()
declare -a allowed_trees=()
declare -a required_paths=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
    entry=${entry%$'\r'}
    [[ -z "$entry" || "$entry" == \#* ]] && continue
    if [[ "$entry" == \?* ]]; then
        entry=${entry#\?}
    else
        required_paths+=("$entry")
    fi
    if [[ -z "$entry" || "$entry" == "." || "$entry" == ./* || "$entry" == /* ||
        "$entry" == ".." || "$entry" == ../* || "$entry" == */../* || "$entry" == */.. ]]; then
        echo "Unsafe PUBLIC-FILES entry: $entry" >&2
        exit 1
    fi
    if [[ "$entry" == */ ]]; then
        allowed_trees+=("$entry")
    else
        allowed_files+=("$entry")
    fi
done < "$allowlist"

is_allowed() {
    local candidate=$1 allowed
    for allowed in "${allowed_files[@]}"; do
        [[ "$candidate" == "$allowed" ]] && return 0
    done
    for allowed in "${allowed_trees[@]}"; do
        [[ "$candidate" == "$allowed"* ]] && return 0
    done
    return 1
}

while IFS= read -r -d '' file; do
    relative_path=${file#"$target/"}
    if ! is_allowed "$relative_path"; then
        echo "File is outside the public allowlist: $relative_path" >&2
        exit 1
    fi
    case "$relative_path" in
        docs/images/*.png|docs/images/*.webp) ;;
        docs/images/*)
            echo "Only PNG and WebP files are allowed in docs/images: $relative_path" >&2
            exit 1
            ;;
    esac
    case "/$relative_path" in
        */AGENTS.md|*/artifacts/*|*/research/*|*/discover-probe/*|*/local.properties|*/.env|*/.env.*|*.jks|*.keystore|*.p12|*.pem|*.key)
            echo "Private or credential-bearing path found in public source: $relative_path" >&2
            exit 1
            ;;
    esac
done < <(find "$target" -type f -print0)

for required in "${required_paths[@]}"; do
    if [[ ! -e "$target/${required%/}" ]]; then
        echo "Public source is missing required allowlisted path: $required" >&2
        exit 1
    fi
done

while IFS= read -r -d '' text_file; do
    if LC_ALL=C grep -E -q -- '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|ghp_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{40,}|sk-[0-9A-Za-z_-]{20,}|/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/' "$text_file"; then
        echo "Possible credential or private local path found in: ${text_file#"$target/"}" >&2
        exit 1
    fi
done < <(find "$target" -type f \( -name '*.gradle' -o -name '*.kts' -o -name '*.kt' -o -name '*.java' \
    -o -name '*.xml' -o -name '*.md' -o -name '*.properties' -o -name '*.sh' -o -name '*.yml' \
    -o -name '*.yaml' -o -name '*.txt' \) -print0)

echo "Public source check passed: $target"
