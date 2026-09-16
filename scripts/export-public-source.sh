#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd -P)
allowlist="$repository_root/PUBLIC-FILES"
destination=${1:-"$repository_root/dist/MiDuo-1.0.2-source"}

if [[ ! -f "$allowlist" || -L "$allowlist" ]]; then
    echo "PUBLIC-FILES must be a regular file inside the repository." >&2
    exit 1
fi

reject_symlink_components() {
    local relative_path=$1 cursor=$repository_root component
    local -a components=()
    IFS=/ read -r -a components <<< "$relative_path"
    for component in "${components[@]}"; do
        cursor="$cursor/$component"
        if [[ -L "$cursor" ]]; then
            echo "Public source path contains a symlink component: $relative_path" >&2
            exit 1
        fi
    done
}

if [[ "$destination" != /* ]]; then
    destination="$PWD/$destination"
fi
if [[ -e "$destination" ]]; then
    echo "Refusing to replace existing export destination: $destination" >&2
    exit 1
fi

declare -a public_files=()
while IFS= read -r entry || [[ -n "$entry" ]]; do
    entry=${entry%$'\r'}
    [[ -z "$entry" || "$entry" == \#* ]] && continue
    optional=false
    if [[ "$entry" == \?* ]]; then
        optional=true
        entry=${entry#\?}
    fi
    if [[ -z "$entry" || "$entry" == "." || "$entry" == ./* || "$entry" == /* ||
        "$entry" == ".." || "$entry" == ../* || "$entry" == */../* || "$entry" == */.. ]]; then
        echo "Unsafe PUBLIC-FILES entry: $entry" >&2
        exit 1
    fi
    entry_path=${entry%/}
    reject_symlink_components "$entry_path"

    source_path="$repository_root/$entry_path"
    if [[ ! -e "$source_path" ]]; then
        if [[ "$optional" == true ]]; then
            continue
        fi
        echo "Required public source path is missing: $entry" >&2
        exit 1
    fi
    if [[ -L "$source_path" ]] || [[ -n "$(find "$source_path" -type l -print -quit 2>/dev/null)" ]]; then
        echo "Public source paths must not contain symlinks: $entry" >&2
        exit 1
    fi
    if [[ -d "$source_path" ]]; then
        physical_source=$(cd "$source_path" && pwd -P)
    else
        physical_source=$(cd "$(dirname "$source_path")" && pwd -P)/$(basename "$source_path")
    fi
    case "$physical_source" in
        "$repository_root"|"$repository_root"/*) ;;
        *)
            echo "Public source path escapes the repository: $entry" >&2
            exit 1
            ;;
    esac
    if [[ -d "$source_path" ]]; then
        if [[ -n "$(find "$source_path" ! -type d ! -type f -print -quit)" ]]; then
            echo "Public source paths may contain only regular files: $entry" >&2
            exit 1
        fi
        while IFS= read -r -d '' file; do
            public_files+=("${file#"$repository_root/"}")
        done < <(find "$source_path" -type f -print0)
    elif [[ -f "$source_path" ]]; then
        public_files+=("$entry")
    else
        echo "Public source path is not a regular file or directory: $entry" >&2
        exit 1
    fi
done < "$allowlist"

mkdir -p "$destination"
for relative_path in "${public_files[@]}"; do
    mkdir -p "$destination/$(dirname "$relative_path")"
    if [[ $(uname -s) == Darwin ]]; then
        COPYFILE_DISABLE=1 cp -X -p "$repository_root/$relative_path" "$destination/$relative_path"
    else
        cp -p "$repository_root/$relative_path" "$destination/$relative_path"
    fi
done

"$repository_root/scripts/check-public-source.sh" "$destination"
echo "Public source exported to $destination"
