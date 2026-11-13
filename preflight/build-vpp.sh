#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
if [[ $# != 3 ]]; then
  echo "Usage: bash preflight/build-vpp.sh LOCAL_VPP_REPO NEW_CHECKOUT BUILD_DIRECTORY" >&2
  exit 2
fi
vpp_repo=$(realpath "$1")
vpp_checkout=$(realpath -m "$2")
vpp_build=$(realpath -m "$3")
if [[ -e "$vpp_checkout" || -e "$vpp_build" ]]; then
  echo "Checkout and build paths must be new; existing work is never reset." >&2
  exit 2
fi
git clone --shared --no-checkout "$vpp_repo" "$vpp_checkout"
git -C "$vpp_checkout" checkout --detach v26.10
test "$(git -C "$vpp_checkout" rev-parse HEAD)" = 4acb320269bc4f3533e35c80afedac38853b2f60
cmake -S "$vpp_checkout/src" -B "$vpp_build" -G Ninja \
  -DCMAKE_BUILD_TYPE=release -DCMAKE_C_FLAGS=-std=gnu11 \
  -DCMAKE_INSTALL_PREFIX="$vpp_build/install" \
  -DVPP_USE_LTO=OFF -DVPP_BUILD_VCL_BAPI=OFF -DVPP_PLUGINS=none
cmake --build "$vpp_build" --target vpp vppctl vppcom -j "${VCL_BUILD_JOBS:-4}"
