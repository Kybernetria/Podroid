#!/bin/sh
# Remove only obsolete system-layer paths and normalize the AVF seed table.
# The helper has a fixed path set and never names or traverses /mnt/persist or
# workload roots. Every parent is opened descriptor-relative with O_NOFOLLOW.
set -eu

immutable_root=${PODROID_IMMUTABLE_ROOT:?immutable migration root is required}
target_root=${PODROID_MIGRATION_TARGET_ROOT:-/}
exec "$immutable_root/usr/local/bin/podroid-migrate-safe" apply-31 "$target_root"
