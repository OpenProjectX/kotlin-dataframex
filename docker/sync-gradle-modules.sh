#!/bin/sh
set -eu

cache_root="${1:-/root/.gradle/caches/modules-2/files-2.1}"
m2_root="${2:-/root/.m2/repository}"

if [ ! -d "$cache_root" ]; then
    echo "Gradle module cache does not exist: $cache_root" >&2
    exit 1
fi

count=0
find "$cache_root" -type f -name '*.module' -print | while IFS= read -r module; do
    relative=${module#"$cache_root"/}
    group=${relative%%/*}
    remainder=${relative#*/}
    artifact=${remainder%%/*}
    remainder=${remainder#*/}
    version=${remainder%%/*}
    filename=${relative##*/}

    group_path=$(printf '%s' "$group" | tr '.' '/')
    destination="$m2_root/$group_path/$artifact/$version"
    mkdir -p "$destination"
    cp "$module" "$destination/$filename"
    count=$((count + 1))
    printf '%s\n' "$count" > /tmp/dataframex-module-count
done

count=$(cat /tmp/dataframex-module-count 2>/dev/null || printf '0')
rm -f /tmp/dataframex-module-count
if [ "$count" -eq 0 ]; then
    echo "No Gradle .module files found under $cache_root" >&2
    exit 1
fi

echo "Synced $count Gradle .module files into $m2_root"
