#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
probe=$(mktemp)
trap 'rm -f "$probe"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -I "${VPP_INCLUDE_DIR:-/usr/include}" "$project_root/src/test/native/abi_probe.c" -o "$probe"
"$probe"
