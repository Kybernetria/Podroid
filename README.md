<div align="center">

<img src="docs/logo.png" alt="Podroid logo" width="120" />

# Podroid

**Run a real minimal Alpine Linux VM on an Android phone. No root required.**

Podroid boots an AArch64 direct-kernel guest through QEMU TCG on stock Android or AVF/pKVM on supported devices. The system image is read-only; package installs and user changes persist on a writable ext4 overlay.

[![Release](https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&style=flat-square&label=release&color=blue)](https://github.com/ExTV/Podroid/releases)
[![License](https://img.shields.io/github/license/ExTV/Podroid?style=flat-square)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![arm64](https://img.shields.io/badge/arch-arm64-orange?style=flat-square)

</div>

## Minimal guest profile

The shipped Alpine 3.23 image deliberately contains only the reviewed explicit package set:

- `alpine-base`, `openrc`, and `busybox-openrc`
- `iproute2` for QEMU static SLIRP networking and AVF DHCP
- `dropbear` and `dropbear-openrc`, configured for public-key authentication only
- `ca-certificates`; `apk` remains available through Alpine base

Docker, Podman, LXC, X11/VNC, PulseAudio, desktop/font packages, predefined workload services, and container backup/status helpers are **not bundled**. They are not part of the minimal base-image contract. The inherited Android X11 UI remains during the staged application refactor, but this guest image does not provide an X11/audio server for it.

## Preserved VM contract

- OpenRC-managed boot with the app-owned `hvc0` console/getty and stable `Ready!` boot markers
- QEMU and AVF networking, terminal control, Downloads/9p sharing, and guest-to-Android host bridge
- Read-only SquashFS system image plus persistent ext4 overlay and versioned migrations
- Validated MVP VM identity `default`, with all VM files confined to `filesDir/instances/default`; legacy root-level VM files move there without overwrite before launch
- `/dev/net/tun`, FUSE, shared-mount, cgroup v2, ZRAM, and basic OOM prerequisites for later guest-side orchestration
- No default password, bundled SSH key, or generated host key in source

Existing `/mnt/persist` data is retained across image upgrades. Migration 31 removes obsolete system startup/config/helper paths only; it does not delete old container or user data directories.

## Quick start

1. Build or install the APK.
2. Tap **Start VM**, wait for **Ready!**, and open the terminal.
3. Use Alpine's package manager for additional guest software:

```sh
apk update
apk add <package>
```

To use SSH, enable it in Settings and first provision your public key from the app-owned guest console:

```sh
mkdir -p /root/.ssh
chmod 700 /root/.ssh
# append your public key to /root/.ssh/authorized_keys, then:
chmod 600 /root/.ssh/authorized_keys
ssh root@<phone-ip> -p 9922
```

Password authentication is disabled.

## Build and verification

```sh
CONTAINER_ENGINE=podman ./build-all.sh rootfs
./gradlew :app:testDebugUnitTest assembleDebug
```

The rootfs command verifies both credential policy and the minimal package/path/boot contract before reporting success. Generated kernel, rootfs, native, and APK artifacts are ignored and must not be committed.

Per-component toolchain details are in [CONTRIBUTING.md](CONTRIBUTING.md). Architecture decisions and the ordered implementation backlog are under [docs/](docs/README.md).

## Contributing

Keep changes scoped to the ordered ticket or approved architecture decision. Run the narrow source tests first, then the rootfs verifier and Android unit/assemble checks. A physical QEMU and AVF boot remains required before declaring the guest boot milestone complete.

## Credits and license

Podroid builds on [QEMU](https://www.qemu.org), [Alpine Linux](https://alpinelinux.org), and the [Termux terminal emulator](https://github.com/termux/termux-app). See [CREDITS.md](CREDITS.md). Licensed under [GPLv2](LICENSE).
