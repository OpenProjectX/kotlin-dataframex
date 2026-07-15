#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <dependency-image> <dataframex-version>" >&2
    exit 2
fi

image=$1
dataframex_version=$2
work_root=${RUNNER_TEMP:-/tmp}
work_dir=$(mktemp -d "$work_root/dataframex-offline.XXXXXX")
offline_repo=$work_dir/m2
offline_gradle_home=$work_dir/gradle-home
data_container=
console_container=

cleanup() {
    if [[ -n $data_container ]]; then
        docker rm -f "$data_container" >/dev/null 2>&1 || true
    fi
    if [[ -n $console_container ]]; then
        docker rm -f "$console_container" >/dev/null 2>&1 || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

mkdir -p "$offline_repo" "$offline_gradle_home"
data_container=$(docker create "$image")
docker cp "$data_container:/m2/repository/." "$offline_repo"
docker rm "$data_container" >/dev/null
data_container=

console_container=$(docker run --detach \
    --publish 127.0.0.1::5000 \
    --publish 127.0.0.1::8080 \
    ghcr.io/openprojectx/crux-console:latest)
console_port=$(docker port "$console_container" 5000/tcp | head -n 1 | awk -F: '{print $NF}')
node_port=$(docker port "$console_container" 8080/tcp | head -n 1 | awk -F: '{print $NF}')
console_url="http://127.0.0.1:$console_port/console/api/query"
node_url="http://127.0.0.1:$node_port"

ready=false
for _ in $(seq 1 90); do
    if curl --fail --silent --show-error "$node_url/" >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 2
done
if [[ $ready != true ]]; then
    docker logs "$console_container"
    echo "Crux Console did not become ready" >&2
    exit 1
fi

curl --fail --silent --show-error \
    --header 'Content-Type: application/edn' \
    --data-binary '[[:crux.tx/put {:crux.db/id :ticker/acme :ticker/name "ACME" :ticker/price 42}] [:crux.tx/put {:crux.db/id :ticker/jetbrains :ticker/name "JetBrains" :ticker/price 73}]]' \
    "$node_url/tx-log" >/dev/null

query='{:find [ticker name price] :where [[ticker :ticker/name name] [ticker :ticker/price price]] :order-by [[price :asc]]}'
indexed=false
for _ in $(seq 1 60); do
    response=$(curl --fail --silent --show-error \
        --header 'Accept: application/edn' \
        --header 'Content-Type: application/edn' \
        --data-binary "$query" \
        "$console_url" || true)
    if [[ $response == *JetBrains* ]]; then
        indexed=true
        break
    fi
    sleep 1
done
if [[ $indexed != true ]]; then
    docker logs "$console_container"
    echo "Seeded Crux documents were not indexed in time" >&2
    exit 1
fi

# This command may download the Gradle distribution, but it resolves no project dependencies.
GRADLE_USER_HOME="$offline_gradle_home" ./gradlew --version >/dev/null

OFFLINE_M2_REPO="$offline_repo" \
DATAFRAMEX_VERSION="$dataframex_version" \
CRUX_CONSOLE_QUERY_URL="$console_url" \
GRADLE_USER_HOME="$offline_gradle_home" \
    ./gradlew --no-daemon --no-configuration-cache --offline \
        -p docker/offline-smoke-test \
        clean build :kotlin-app:verifyPublishedExample \
        --stacktrace
