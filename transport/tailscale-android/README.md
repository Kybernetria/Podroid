# Tailscale Android transport

This directory owns Podroid's Android-side Tailscale adapter boundary. Issue #15 currently implements only the reproducible build, provenance, JNI-linkage, and debug APK-packaging scaffold; it does not yet implement or enable a management transport.

## Debug native build

An ordinary `:app:assembleDebug`:

1. verifies the exact clean `third_party/libtailscale` gitlink, commit/tree, BSD-3-Clause license hash, Go/module pins, Go 1.25.5 executable, and NDK 28.2.13676358 API 26 AArch64 clang;
2. builds the unmodified official source with `GOOS=android`, `GOARCH=arm64`, `CGO_ENABLED=1`, and `-buildmode=c-shared`;
3. compiles [`jni/podroid_tailscale_jni.c`](jni/podroid_tailscale_jni.c) as a project-owned JNI shared library linked to the official `tailscale.h` API;
4. generates both `arm64-v8a` libraries and `libtailscale-provenance.json` below `app/build/generated/libtailscale/debug`; and
5. verifies the final APK's sole ABI, AArch64 ELF identity, 16 KiB `PT_LOAD` alignment, `DT_NEEDED` linkage, provenance hashes, and manifest.

Supply toolchains explicitly:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  PODROID_GO=/absolute/path/to/go1.25.5/bin/go \
  PODROID_ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358 \
    ./gradlew :app:assembleDebug
'
```

The JNI symbol is a debug linkage probe only and is not loaded or called by production Kotlin code. The generated libraries are registered only on the Android `debug` source set. Release packaging remains disabled, no generated binary is source-controlled, and no `VpnService` is declared.

## Runtime scope still outstanding

A future adapter may use official Tailscale/libtailscale components and Android network hooks for Tailscale or external Headscale coordination during host-management bootstrap. It must add bounded lifecycle, network-change/DNS integration, authenticated peer identity, cancellation, and transport/API policy before accepting remote management traffic.

This component does not run guest workload networking or embed Headscale. The guest's Linux `tailscaled` identity remains separate.
