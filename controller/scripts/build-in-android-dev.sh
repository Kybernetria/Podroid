#!/usr/bin/env bash
set -euo pipefail

box_name="${PODROID_DISTROBOX:-android-dev}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if ! command -v distrobox >/dev/null 2>&1; then
    echo "distrobox is required to enter ${box_name}" >&2
    exit 1
fi

distrobox enter "${box_name}" -- bash -lc '
    set -euo pipefail
    cd "$1/controller"
    cargo fmt --all --check
    cargo check --workspace --locked
    cargo test --workspace --locked
    cargo build --package podroid-desktop-controller --locked
' bash "${repository_root}"
