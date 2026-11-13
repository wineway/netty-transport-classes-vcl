#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"
if [[ ! -f target/benchmark-classpath ]]; then
    echo 'Build first: mvn -Pbenchmark -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath' >&2
    exit 1
fi
# A new user/network namespace contains both ends of the test network.
exec unshare --user --map-root-user --net python3 "$project_root/scripts/benchmark.py" "$@"
