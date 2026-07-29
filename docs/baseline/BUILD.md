# Android, guest, and QEMU build baseline

This procedure separates the verified Gradle-only build and Podman-built rootfs from the remaining unverified native/VM stages. All host-side commands intentionally enter the `android-dev` distrobox.

## Reproducibility status

| Layer | Baseline status |
|---|---|
| Android unit tests and Gradle-only debug APK | Verified successful with `:app:testDebugUnitTest assembleDebug` |
| Kernel/initramfs | Not rebuilt in this environment; these stages remain Docker-only |
| Alpine squashfs rootfs | Verified with the supported `CONTAINER_ENGINE=podman` path and explicit artifact checks |
| QEMU, libslirp, bridge, and launcher | Not rebuilt in this environment; this stage remains Docker-only |
| Full APK assembled from all freshly generated native/VM artifacts | Not verified |
| Device install, RAM, QEMU boot, and AVF boot | Pending |

Docker is absent in the recorded environment. Podman 5.8.4 is installed, and `build-all.sh rootfs` has an explicit, verified `CONTAINER_ENGINE=podman` path. The kernel, initramfs, and QEMU stages still invoke `docker` directly, so the complete native/VM build was not run and this document does not claim bit-for-bit or full-build reproducibility.

Confirm the runtime state:

```bash
distrobox enter android-dev -- bash -lc '
  if command -v docker >/dev/null 2>&1; then docker --version; else echo "docker: absent"; fi
  podman --version
'
```

Recorded output was `docker: absent` and `podman version 5.8.4`.

## Host prerequisites for credential and rootfs verification

The ordinary Gradle `preBuild` always runs source verification. When `app/src/main/assets/alpine-rootfs.squashfs` exists, a separate task declares that exact artifact as an input and verifies it before packaging; release packaging requires the file. A Gradle-only debug build remains supported when it is absent. Source verification requires Python 3 and OpenSSL; OpenSSL is used by the actual root-hash generator, which the verifier executes twice. Artifact verification additionally requires `unsquashfs`:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  command -v python3
  python3 --version
  command -v openssl
  openssl version
  python3 tests/verify_guest_credentials.py
  python3 tests/verify_minimal_guest.py
'
```

A rootfs build additionally requires Docker or Podman and `unsquashfs` from the `squashfs-tools` package on the host. `build-all.sh rootfs` preflights Python and `unsquashfs`, then explicitly verifies the newly produced artifact. The verifier limits the compressed size, superblock inode count, listing output, summed expanded entry sizes, CPU concurrency, command duration, and each `-cat` result before semantic checks. It uses `unsquashfs -cat` rather than extraction, so artifact symlinks cannot redirect writes or reads onto the host.

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  command -v podman
  command -v unsquashfs
  unsquashfs -version
  CONTAINER_ENGINE=podman ./build-all.sh rootfs
  python3 tests/verify_guest_credentials.py app/src/main/assets/alpine-rootfs.squashfs
  python3 tests/verify_minimal_guest.py app/src/main/assets/alpine-rootfs.squashfs
'
```

The minimal-guest verifier checks the exact explicit package manifest, resolved `/lib/apk/db/installed` closure, forbidden source/artifact paths, required OpenRC/runlevel and backend contracts, migration 31, and bounded artifact metadata. Ticket 5 before/after package and size evidence is recorded in `docs/baseline/MINIMAL_GUEST.md`.

`openssl` and Alpine signing keys are installed inside the rootfs builder image. The rootfs package installation explicitly uses the copied Alpine keys and does not use apk's `--allow-untrusted` bypass.

## Pinned and selected inputs

| Input | Baseline value | Source/qualification |
|---|---:|---|
| Gradle wrapper | 9.3.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle distribution SHA-256 | `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06` | Wrapper checksum |
| Gradle launcher JVM | Java 17 | Recorded launcher was OpenJDK 17.0.18; app source/target compatibility is 17 |
| Gradle daemon JVM criteria | Java 21, any vendor, non-native-image | `gradle/gradle-daemon-jvm.properties` |
| Android Gradle Plugin | 9.1.0 | Version catalog |
| Kotlin | 2.2.21 | Version catalog |
| Gradle Android NDK | 28.2.13676358 | Installed/selected baseline NDK; `ndkVersion` is not explicitly declared in the app module |
| Android native ABI/API | `arm64-v8a`, API 26 | App ABI filter and native compiler target |
| Linux kernel | 7.0.10 | `gradle.properties` and Docker build argument |
| QEMU | 11.0.0 | `gradle.properties` and Docker build argument |
| QEMU cross-build NDK | r27c | Downloaded explicitly by `Dockerfile` |
| Alpine | 3.23.x | Rootfs base stages pin the Alpine 3.23 multi-arch digest; minirootfs 3.23.4 aarch64 is SHA-256 pinned; kernel/initramfs downloads currently use 3.23.3 |

Remaining reproducibility gaps are baseline facts:

1. Alpine package versions and Debian `apt` packages are not repository-snapshot locked. The minimal rootfs does fail closed unless the resolved Alpine package-name closure exactly matches its reviewed 41-package lock.
2. `libucontext` is cloned with `--depth=1` and no commit/tag, and the Meson installation is also unpinned.
3. Other Debian image tags remain mutable.

## Shared Android SDK through `local.properties`

`local.properties` is ignored by Git and is worktree-local. The preserved main checkout has the canonical SDK setting:

```text
sdk.dir=/var/home/kyvernitria/Applications/Applications_stable_maintained/Android/android-sdk
```

A linked worktree can share that file instead of copying a machine path that may drift. From the linked worktree, create a symlink to the main checkout's ignored file:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  common_dir=$(realpath "$(git rev-parse --git-common-dir)")
  shared_local_properties="$(dirname "$common_dir")/local.properties"
  test -f "$shared_local_properties"
  test ! -e local.properties
  ln -s "$shared_local_properties" local.properties
  ls -l local.properties
  sed -n "s/^sdk.dir=/sdk.dir=/p" local.properties
'
```

If this is not a linked worktree, or the canonical file does not exist, create one with the distrobox-visible absolute SDK path:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  sdk=/absolute/path/visible/inside/android-dev/android-sdk
  test -d "$sdk"
  printf "sdk.dir=%s\n" "$sdk" > local.properties
'
```

The path must resolve inside `android-dev`. Do not commit `local.properties`.

Inspect the SDK/NDK baseline:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  sdk=$(sed -n "s/^sdk.dir=//p" local.properties)
  test -d "$sdk/platforms/android-36.1"
  test -d "$sdk/build-tools/36.0.0"
  test -d "$sdk/ndk/28.2.13676358"
  printf "SDK=%s\n" "$sdk"
'
```

## Verify Gradle and Java selection

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  java -version
  ./gradlew --version
  grep -F "distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06" gradle/wrapper/gradle-wrapper.properties
'
```

The expected Gradle summary reports a Java 17 launcher and daemon criteria compatible with Java 21. The daemon may be provisioned separately by Gradle; do not infer that the launcher itself is Java 21.

## Verified Gradle-only command

Before interpreting the result, list the generated assets. Missing files prove only that the APK is Gradle-only; present files may be stale and must be traced to a specific native build.

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  for path in \
    app/src/main/assets/vmlinuz-virt \
    app/src/main/assets/initrd.img \
    app/src/main/assets/alpine-rootfs.squashfs \
    app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so \
    app/src/main/jniLibs/arm64-v8a/libslirp.so \
    app/src/main/jniLibs/arm64-v8a/libpodroid-bridge.so \
    app/src/main/jniLibs/arm64-v8a/libpodroid-launcher.so
  do
    if test -e "$path"; then stat -c "%n %s bytes" "$path"; else echo "MISSING $path"; fi
  done
'
```

Run the recorded verification/build command:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  ./gradlew :app:testDebugUnitTest assembleDebug
'
```

This compiles the Android modules and the vendored Termux JNI with Gradle NDK 28.2.13676358. It does not invoke the Docker-backed kernel, initramfs, rootfs, or QEMU stages.

## Full build entry points (remaining stages not verified here)

`build-all.sh all` coordinates every stage and still requires a working `docker` command with BuildKit-compatible behavior because the kernel, initramfs, and QEMU stages are Docker-only:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  command -v docker
  docker version
  ./build-all.sh all
'
```

Narrow stages are available for diagnosis. The rootfs stage also supports Podman through the explicit container-engine seam:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  ./build-all.sh kernel
  ./build-all.sh initramfs
  CONTAINER_ENGINE=podman ./build-all.sh rootfs
  ./build-all.sh qemu
  ./build-all.sh apk
'
```

Do not report these stages as successful unless each command actually completes and the extracted artifacts are measured. Podman support is intentionally limited to the rootfs stage; converting the Docker-only kernel, initramfs, or QEMU stages would be a separate implementation change requiring validation of build output, extraction, networking, and `--output` behavior.

## Native output checks for a future full rebuild

The native Android/QEMU path targets API 26 and 16-KiB pages. After a full rebuild, retain the script's ELF check and independently inventory the APK:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  apk=app/build/outputs/apk/debug/app-debug.apk
  test -f "$apk"
  stat -c "%n %s bytes" "$apk"
  unzip -l "$apk" | awk '\''/lib\/arm64-v8a\/.*\.so$/ {print $4}'\'' | sort
'
```

A complete APK should be distinguished from the 38,033,502-byte Gradle-only baseline by recording hashes and the presence and provenance of the kernel, initramfs, rootfs, QEMU, libslirp, bridge, and launcher artifacts.
