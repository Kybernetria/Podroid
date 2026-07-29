# Alpine Direct Profile

Alpine 3.23 is the sole initial known-good guest profile. Its current realization remains in the inherited `build-rootfs/`, `init-podroid`, kernel, and Android engine paths until the versioned profile schema is introduced; this directory documents the profile boundary without moving backend ownership.

## Base-image contract

The direct-kernel SquashFS explicitly installs only:

- `alpine-base`
- `openrc`
- `busybox-openrc`
- `iproute2`
- `dropbear`
- `dropbear-openrc`
- `ca-certificates`

`apk-tools` is retained through Alpine base. The resolved closure is checked from `/lib/apk/db/installed` in the built artifact.

The base does not bundle Docker, Podman, LXC, a container runtime, X11/VNC, PulseAudio, desktop/font packages, predefined workload services, or appliance backup/statistics helpers. Later orchestrator tickets must install and configure their guest-owned runtime explicitly; Android must not become workload-state owner.

## Preserved boot and storage behavior

- BusyBox init starts OpenRC and the Podroid default runlevel.
- The app-owned `hvc0` getty, resize channel, and Android boot markers remain stable.
- Dropbear is public-key-only and no key or default credential is bundled.
- QEMU uses static SLIRP addressing; AVF uses bounded DHCP.
- QEMU virtio-9p and AVF 9p-over-vsock Downloads paths remain.
- Host bridge, AVF vsock control port 9100, migration, and overlay normalization services remain.
- The read-only SquashFS lower and persistent ext4 upper retain user state across updates.
- Shared mount propagation, `/dev/net/tun`, `/dev/fuse`, cgroup v2, ZRAM, and basic OOM policy remain as future workload prerequisites.

Migration 31 removes obsolete system-layer startup/config/helper paths but never traverses or removes `/mnt/persist`, `/var/lib`, home directories, or user data disks.

## Verification

`tests/verify_minimal_guest.py` fail-closes on source drift and inspects the generated SquashFS with bounded single-processor `unsquashfs` calls. `build-all.sh rootfs` and Gradle verification run it alongside the guest credential verifier.
