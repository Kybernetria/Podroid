# Ticket 5 minimal Alpine guest evidence

This record covers the bounded guest-image feature group at versionCode 31. It does not claim a physical-device boot: no Android device was available, so QEMU and AVF boot remain an explicit pending gate.

## Before and after

| Metric | Before | After |
|---|---:|---:|
| Resolved Alpine packages | 255 | 41 |
| Rootfs artifact bytes (`stat -c %s`) | 228,225,024 | 8,953,856 |
| SquashFS filesystem bytes (`unsquashfs -s`) | Not recorded | 8,950,019 |

The before values are the supplied ticket/scout baseline for the previous artifact; that artifact was not regenerated in this worktree. The after artifact was built with live Alpine 3.23 repositories by:

```sh
CONTAINER_ENGINE=podman ./build-all.sh rootfs
```

After artifact SHA-256 for this evidence run:

```text
89ab4d5649e465a1c02b6212af06401c096b7fd4b42e620fd4e93d673281b82c
```

Alpine packages and image tags remain unpinned to immutable repository snapshots, so the hash, exact dependency versions, and compressed byte count may change on a later rebuild even when the reviewed explicit package manifest does not.

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

No Docker, Podman, LXC, container-runtime, X11/VNC, PulseAudio, desktop, or font package matched the forbidden closure policy. The artifact verifier also rejected any occurrence of the retired X11 profile/service, container storage config, appliance logo, backup/statistics helpers, or representative removed binaries.

## Preserved checks

The source and artifact verifiers require the OpenRC services/runlevels, hvc0 console/getty, public-key-only Dropbear config, QEMU static and AVF DHCP network logic, CA/apk packages, boot markers, both Downloads/9p paths, migration 31, host bridge/vsock helpers, control port 9100, `/dev/net/tun`, FUSE, cgroup2, shared-mount, ZRAM, and OOM policy tokens.

Migration tests execute migration 31 twice against a temporary root and verify obsolete system paths are removed while `/mnt/persist` container directories, `/var/lib` data, home data, and user files remain unchanged.

## Pending physical gate

A physical Android QEMU boot and AVF boot are unavailable in this environment. Required follow-up evidence is a clean and upgraded boot on each backend through `Ready!`, console/getty interaction, network address acquisition, public-key SSH, Downloads sharing, host bridge/control operation, and confirmation that pre-existing persistent data remains intact.
