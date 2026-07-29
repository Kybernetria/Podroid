# Ticket 5 minimal Alpine guest evidence

This record covers the bounded guest-image feature group at versionCode 31. It does not claim a physical-device boot: no Android device was available, so QEMU and AVF boot remain an explicit pending gate.

## Before and after

| Metric | Before | After |
|---|---:|---:|
| Resolved Alpine packages | 255 | 41 |
| Rootfs artifact bytes (`stat -c %s`) | 228,225,024 | 9,035,776 |
| SquashFS filesystem bytes (`unsquashfs -s`) | Not recorded | 9,034,782 |
| Strict debug APK bytes (`stat -c %s`) | Not recorded | 55,908,661 |

The before values are the supplied ticket/scout baseline for the previous artifact; that artifact was not regenerated in this worktree. The final source-only commit excludes generated rootfs/APK artifacts. Final evidence artifacts were built inside the `android-dev` distrobox with live Alpine 3.23 repositories and strict rootfs-in-APK checks:

```sh
CONTAINER_ENGINE=podman ./build-all.sh rootfs
./build-all.sh apk
python3 tests/verify_guest_credentials.py \
  --apk app/build/outputs/apk/debug/app-debug.apk --require-rootfs
python3 tests/verify_minimal_guest.py \
  --apk app/build/outputs/apk/debug/app-debug.apk --require-rootfs
```

Final artifact SHA-256 values for this evidence run:

```text
31fbe55365c182d51bb678679a09cc229a749aa6b1764cc6c138ca050e712bd0  app/src/main/assets/alpine-rootfs.squashfs
427f00dd3e08e679dad8c4d8fca6169dd55e274efe40e4083a47e9465e3454c4  app/build/outputs/apk/debug/app-debug.apk
```

The rootfs build reported `minimal guest artifact verification passed: 41 resolved packages, 9034782 SquashFS bytes` and `Guest credential source and explicit artifact verification passed.` The two explicit strict APK verifier commands then reported `Guest credential source and packaged APK verification passed` and `minimal guest packaged APK verification passed`; both used `--require-rootfs`, so omission of the packaged SquashFS would fail.

The Alpine 3.23 base image is pinned by multi-architecture manifest digest and the Alpine 3.23.4 aarch64 minirootfs archive is pinned by SHA-256. Package repository URLs are not snapshot URLs, but the build and artifact verifier now require the exact reviewed 41-package name closure, failing closed on dependency-name drift. Package versions and compressed bytes may still change while that exact name closure remains available.

## Reviewed explicit package set

```text
alpine-base
openrc
busybox-openrc
iproute2
dropbear
dropbear-openrc
ca-certificates
```

## Resolved artifact closure

The fail-closed verifier read `/lib/apk/db/installed` directly from the generated SquashFS and reported:

```text
alpine-base
alpine-baselayout
alpine-baselayout-data
alpine-conf
alpine-keys
alpine-release
apk-tools
bridge
busybox
busybox-binsh
busybox-mdev-openrc
busybox-openrc
busybox-suid
ca-certificates
ca-certificates-bundle
dropbear
dropbear-openrc
ifupdown-ng
ifupdown-ng-iproute2
iproute2
iproute2-minimal
iproute2-ss
iproute2-tc
libapk
libcap2
libcrypto3
libelf
libmnl
libssl3
libxtables
mdev-conf
musl
musl-utils
openrc
openrc-user
scanelf
skalibs-libs
ssl_client
utmps-libs
zlib
zstd-libs
```

The build and verifier require this exact 41-package lock: additions and removals both fail. The artifact verifier also rejects any occurrence of the retired X11 profile/service, container storage config, appliance logo, backup/statistics helpers, or representative removed binaries.

## Preserved checks

The source and artifact verifiers require the OpenRC services/runlevels, hvc0 console/getty, public-key-only Dropbear config, QEMU static and AVF DHCP network logic, CA/apk packages, boot markers, both Downloads/9p paths, migration 31, host bridge/vsock helpers, control port 9100, `/dev/net/tun`, FUSE, cgroup2, shared-mount, ZRAM, and OOM policy tokens.

Migration tests execute the static no-follow helper twice against a temporary root and verify obsolete system paths are removed, copied-up AVF seeds become exactly `9100 ctl`, and `/mnt/persist`, `/var/lib`, home, and user files remain unchanged. Hostile parent and test-root symlinks are rejected. The runner validates the complete bounded, ordered index from `/mnt/lower` before executing immutable scripts and commits the marker through a symlink-safe atomic helper operation. A failed-migration regression verifies that `applied-version` does not advance and that the required `podroid-migrate` → bootstrap → network → `Ready!` dependency chain cannot proceed.

Overlay-normalization regressions require the exact `podroid-overlay-normalize-v1\n` marker payload, force empty/old regular markers to be removed before cleanup reruns, and inject cleanup, publication, temporary rollback-unlink, and rollback-directory-sync failures. A reported final publication sync error may leave the exact marker only after all upper/work cleanup has been durably synced; the test records that state as safe and verifies a retry is idempotent.

## Pending physical gate

A physical Android QEMU boot and AVF boot are unavailable in this environment. Required follow-up evidence is a clean and upgraded boot on each backend through `Ready!`, console/getty interaction, network address acquisition, public-key SSH, Downloads sharing, host bridge/control operation, and confirmation that pre-existing persistent data remains intact.
