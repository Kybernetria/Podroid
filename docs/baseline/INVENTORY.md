# Podroid feature and dependency inventory

This is a **historical** inventory at commit `8ecfefaa3459b7e84c3d6e52c57e8005e289e8e3`, not a description of the current release. “Retain” identifies that earlier VM baseline. “Removable candidate” identifies a bounded optional group as reviewed at that commit; it is not evidence that deleting one file is safe and it is not an approved source change. The current minimal guest does not bundle Docker, Podman, LXC, Xvnc, PulseAudio, desktop/font packages, or their listeners/forwards; see `docs/guide/packages.html` for the current contract.

## Release-blocking credential

> **RELEASE BLOCKER — known root password:** At this baseline, `build-rootfs/build-rootfs.sh` hashed the fixed plaintext password `[REDACTED RETIRED CREDENTIAL]` into `/etc/shadow` and wrote it into `/etc/issue`. This inventory retains the release-blocker record without retaining the plaintext. The defect was not recommended usage; release remained blocked until an approved first-boot credential or disabled-password design replaced it and upgrade behavior was defined.

## Preserved VM baseline

### Engine ownership and lifecycle

- `app/src/main/java/com/excp/podroid/engine/VmEngine.kt` is the backend contract and owns `VmConfig`.
- `app/src/main/java/com/excp/podroid/engine/EngineHolder.kt` selects/routes QEMU or AVF and applies live port-forward diffs.
- `app/src/main/java/com/excp/podroid/service/PodroidService.kt` owns foreground VM lifecycle, launch rules, and shutdown obligations.
- `app/src/main/java/com/excp/podroid/engine/QemuEngine.kt` owns QEMU process construction and supervision. Preserve its machine/CPU/TCG defaults, file paths, socket order, disk behavior, SLIRP setup, and launcher wrapping unless a separately measured architecture change replaces them.

### QEMU control plane

- `app/src/main/java/com/excp/podroid/engine/QmpClient.kt` connects to `filesDir/qmp.sock`, uses a 5-second socket read timeout, negotiates QMP capabilities, handles interleaved events, and applies live host forwards.
- USB passes an Android-opened file descriptor with SCM_RIGHTS to QMP `add-fd`, then uses `device_add usb-host`; cleanup uses `device_del`/`remove-fd`.
- `app/src/main/java/com/excp/podroid/engine/usb/UsbPassthroughManager.kt` owns Android USB consent, connection lifetime, and hot-plug orchestration. QEMU adds `qemu-xhci` only when enabled. AVF intentionally does not expose this QMP-only feature.

### Process launcher

- `podroid-launcher.c` builds as `app/src/main/jniLibs/arm64-v8a/libpodroid-launcher.so`.
- `QemuEngine` executes the launcher before `libqemu-system-aarch64.so`. The launcher sets `PR_SET_PDEATHSIG(SIGKILL)` so QEMU does not survive app death as an orphan.
- `QemuEngine` currently has a direct-QEMU compatibility fallback when the launcher is absent. Preserve launcher packaging and lifecycle semantics; do not treat the fallback as the desired full-build output.

### Persistent storage and system update path

These storage paths are core and are **not** part of the removable Downloads-sharing group:

- `filesDir/storage.img`: writable ext4 persistent overlay disk (`/dev/vda`). It grows but is never automatically shrunk or deleted.
- `filesDir/alpine-rootfs.squashfs`: read-only Alpine system lower disk (`/dev/vdb`).
- `filesDir/vmlinuz-virt` and `filesDir/initrd.img`: kernel and initramfs.
- `init-podroid`: mounts the persistent upper and squashfs lower with plain overlayfs, normalizes legacy upper metadata, and `switch_root`s to OpenRC.
- `build-rootfs/files/etc/init.d/podroid-migrate`, `/etc/podroid/system-version`, and versioned migration hooks preserve in-place upgrades and must not become a second persistence owner.
- `QemuEngine.ensureStorageImage()` bounds requested size in GiB through settings, creates/grows the sparse image, and keeps an existing larger image to avoid corruption.

AVF has backend-specific disk creation in `app/src/main/java/com/excp/podroid/engine/avf/AvfEngine.kt`; it must preserve the same user-data ownership and migration semantics.

### Boot and console paths

Preserve the exact QEMU socket roles and virtio-console order:

| Android `filesDir` path | QEMU/guest endpoint | Ownership |
|---|---|---|
| `serial.sock` | PL011 `/dev/ttyAMA0` | Boot log only; `QemuBootMonitor.kt` writes `console.log` and feeds `BootStageDetector.kt` |
| `terminal.sock` | virtio-console `/dev/hvc0` | Interactive terminal via `libpodroid-bridge.so` |
| `ctrl.sock` | virtio-console `/dev/hvc1` | `RESIZE rows cols` control channel |
| `host.sock` | virtio-console `/dev/hvc2` | Guest-to-Android host bridge |
| `qmp.sock` | QMP Unix socket | Runtime forwarding and USB control |
| `console.log` | persisted diagnostic log | Boot/status evidence exposed by diagnostics |

`BootStageDetector.kt` is backend-neutral and scans bounded rolling console content across read boundaries. `Ready!` is the one-shot transition to `VmState.Running` and terminal auto-start. The guest emits stages from OpenRC services under `build-rootfs/files/etc/init.d/`.

The terminal bridge source is `podroid-bridge.c`; it connects the Termux PTY to `terminal.sock` and sends resize control to `ctrl.sock`. The guest host bridge is built from `build-rootfs/host-bridge/`, while Android transports and dispatch live under `app/src/main/java/com/excp/podroid/engine/hostbridge/`.

### SLIRP network and forwarding

- `Dockerfile` configures QEMU with `--enable-slirp`, and packages `libslirp.so` beside QEMU after fixing its soname/dependency.
- `QemuEngine.buildCommand()` creates `-netdev user,id=net0,ipv6=off` and a `virtio-net-pci` device.
- Cold-start `hostfwd` rules are in the SLIRP netdev string. Live changes go through `QmpClient` human-monitor `hostfwd_add`/`hostfwd_remove`.
- User rules can bind all interfaces; implicit service rules use loopback. Preserve this trust boundary when optional services are removed.

### AVF backend

Retain the backend abstraction and all code under `app/src/main/java/com/excp/podroid/engine/avf/`, including:

- capability/permission detection and diagnostics;
- `AvfReflect.kt` access to Android Virtualization Framework APIs;
- `AvfEngine.kt` VM/disk/lifecycle ownership;
- `ConsoleFanout.kt` for console consumers;
- `VsockControlChannel.kt`, `VsockPortForwarder.kt`, and `VsockUdpForwarder.kt`;
- AVF host transport and guest `podroid-vsock-agent` integration;
- bounded 9p-over-vsock Downloads support if the optional Downloads group remains.

QEMU and AVF transports are intentionally asymmetric. Do not replace backend-specific behavior with QMP assumptions on AVF or vsock assumptions on QEMU.

### 16-KiB Android native path

Retain all of these alignment points:

- QEMU link flags in `Dockerfile`: `-Wl,-z,max-page-size=16384`.
- Bridge and launcher link commands in `Dockerfile`, targeting `aarch64-linux-android26` with the same maximum page size.
- Dependency cross-link flags in `build-tools/cross-android-aarch64.ini`.
- Vendored terminal JNI flags in `terminal-emulator/src/main/jni/Android.mk`.
- ELF program-header verification in `build-all.sh`.
- Legacy JNI packaging in `app/build.gradle.kts`, required because QEMU and helpers are executable ELF files packaged with `.so` names.

## Android features at the recorded baseline commit

- Single-activity Jetpack Compose UI with setup, home/status, terminal, settings, backup, and X11 routes.
- Foreground service, wake lock, notifications, diagnostics export, and DataStore settings.
- Vendored Termux terminal view/emulator with terminal themes, fonts, extra keys, resize, Sixel, and iTerm2 image support.
- QEMU/TCG default backend; optional AVF/pKVM backend with runtime fallback.
- SSH, TCP/UDP forwarding, guest-to-Android notifications/forward requests, USB passthrough on QEMU, and optional Downloads sharing.
- Alpine/OpenRC guest with Podman as the core container runtime, plus Docker and LXC extras.
- In-app X11/RFB viewer and PulseAudio capture.
- English and Chinese resources.

The source manifest declares 12 permissions: Internet/network state, wake lock, vibration, two foreground-service permissions, notifications, three external-storage permissions, and two AVF permissions.

## Removable candidate groups

### 1. X11, VNC, and audio

Current boundary:

- Android implementation: `app/src/main/java/com/excp/podroid/x11/` and `app/src/main/java/com/excp/podroid/ui/screens/x11/`.
- Navigation/entry: `ui/navigation/NavGraph.kt` and the desktop action in `ui/screens/terminal/TerminalScreen.kt`.
- Guest service/config: `build-rootfs/files/etc/init.d/podroid-x11`, `build-rootfs/files/etc/profile.d/podroid-x11.sh`, and its copy/runlevel wiring in `build-rootfs/build-rootfs.sh`.
- Guest packages: `tigervnc`, `pulseaudio`, and `pulseaudio-utils`.
- The inherited Android X11/audio clients still dial TCP 5900 and 4713 when explicitly started, but the host no longer injects or reserves those forwards and the guest no longer seeds listeners. User-created forwards on those ports are ordinary explicit rules.
- Launch config/settings: `VmConfig.x11Dpi`, `podroid.x11.dpi`, X11 DataStore keys, strings, tests, and diagnostics.

Removal must delete the whole group on both backends and release the reserved ports. It must not remove the interactive terminal console or generic forwarding.

### 2. Extra container runtimes

Podman is the retained core. The removable extras are:

- Docker packages/services: `docker`, `docker-openrc`, `docker-cli-compose`, and the `docker` default-runlevel entry.
- LXC packages/services: `lxc`, `lxc-templates`, `lxc-download`, `lxc-openrc`, `lxc-bridge`, the `lxc`/conditional `dnsmasq.lxcbr0` runlevel entries, and any LXC-specific kernel/package assumptions.

Do **not** group Podman, `crun`, `shadow-uidmap`, container storage, cgroup/overlay support, netavark/aardvark, nftables/iptables, or the persistent `storage.img` into this optional deletion. Package dependency closure and guest boot logs must be measured after any reduction.

### 3. Bundled fonts and appearance assets

- `app/src/main/assets/fonts/` contains 13 selectable terminal fonts. The default terminal renderer and a tested fallback must remain if alternates are removed.
- `app/src/main/assets/ui-fonts/` contains 2 app UI fonts; removal requires switching the Compose typography rather than simply deleting assets.
- `app/src/main/assets/colors/` contains 118 terminal color/theme files and is adjacent optional appearance content, although it is not included in the 13-font count.
- Guest X11 font packages `font-misc-misc`, `font-cursor-misc`, and `ttf-dejavu` belong to the X11 dependency closure, not the Android terminal-font group.

Update selectors, defaults, persisted-value fallback, tests, and both language resource sets if assets are removed.

### 4. Downloads storage sharing

This optional group means Android public Downloads sharing, **not VM persistence**:

- Manifest permissions: `MANAGE_EXTERNAL_STORAGE`, legacy `READ_EXTERNAL_STORAGE`, and legacy `WRITE_EXTERNAL_STORAGE`.
- Setup/settings grant UI and `SettingsRepository.KEY_STORAGE_ACCESS_ENABLED`.
- QEMU virtio-9p `-fsdev`/`virtio-9p-pci` path in `QemuEngine` and guest mount logic in `podroid-bootstrap`.
- AVF path: `AvfDownloadsShare.kt`, `engine/avf/ninep/`, `podroid-downloads`, and the vsock-agent rendezvous.
- Backup/status integrations: `ContainerBackupRepository`, `ContainerStatsRepository`, backup UI, `podroid-backup`, and `podroid-update-stats`.

Retain `storage.img`, rootfs overlay/migrations, and storage-size controls. If Downloads sharing is removed, container backup/export needs either explicit removal or a replacement destination; it must not silently claim successful export.

### 5. User-supplied advanced arguments

The removable editor group is the free-form QEMU and kernel argument surface:

- DataStore keys/flows/defaults `qemu_extra_args` and `kernel_extra_cmdline` in `SettingsRepository.kt`.
- `AdvancedFieldsBlock` in `SettingsScreen.kt` and matching ViewModel setters/reset behavior.
- `VmConfig.qemuExtraArgs` and `VmConfig.kernelExtraCmdline` plumbing through `PodroidService`.
- Final argument append in `QemuEngine.buildCommand()` and kernel-command-line append in both QEMU and AVF.

The backend selector currently appears in the same Advanced UI section but is **not** part of this candidate: QEMU/AVF selection must remain reachable if both backends are retained. Preserve safe engine-owned defaults (`-M`, CPU, TCG, memory, disks, networking, consoles, QMP, and required kernel markers); only the user-controlled override surface is optional.

## Dependency inventory

### Android/Gradle

| Group | Current version/input |
|---|---|
| Gradle / AGP / Kotlin | 9.3.1 / 9.1.0 / 2.2.21 |
| KSP / Hilt | 2.3.6 / 2.59.2; Hilt Navigation Compose 1.3.0 |
| AndroidX core/activity/lifecycle | Core KTX 1.16.0; Activity Compose 1.10.1; Lifecycle 2.9.0 |
| Compose | BOM 2026.03.01; UI, graphics, tooling preview, Material 3, window size class, extended icons |
| Navigation | Navigation Compose 2.9.7 |
| Coroutines / DataStore | 1.9.0 / 1.2.1 |
| Terminal | Vendored local `terminal-emulator` and `terminal-view`; catalog reference v0.118.1 documents upstream lineage |
| AVF reflection | HiddenApiBypass 6.1 |
| Tests | JUnit 4.13.2; AndroidX JUnit 1.2.1; Espresso 3.6.1; Compose UI tests via BOM |

### Native/QEMU

| Dependency | Current input |
|---|---|
| QEMU | 11.0.0 |
| QEMU NDK / native API | Android NDK r27c / API 26 |
| PCRE2 | 10.44 |
| libffi | 3.4.6 |
| GLib | 2.82.5 |
| pixman | 0.44.2 |
| attr | 2.5.2 |
| libusb | 1.0.27 |
| libucontext | **Unpinned** shallow clone of the repository default branch |
| Meson and Debian build packages | **Unpinned** to exact versions |
| libslirp | Built through QEMU's configured dependency path and packaged as `libslirp.so` |

### Guest

- Alpine base/minirootfs is 3.23.x, with OpenRC as PID 1.
- Rootfs packages are listed in `build-rootfs/build-rootfs.sh` but are **not version-pinned**; resolution depends on the live Alpine 3.23 main/community repositories.
- Core retained packages include Alpine base/OpenRC, Podman/crun, overlay/network tooling, Dropbear, shell/admin tools, and CA/network clients.
- Optional package closures are listed in the removable groups above.

Capture resolved package versions, image digests, and source hashes before claiming a reproducible full rootfs or QEMU build.
