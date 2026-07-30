# Tailscale Android Transport Spike

Issue #15 adds two deliberately separate pieces:

1. a reproducible debug-only Android `arm64-v8a` build and APK-packaging proof for the exact unmodified official libtailscale pin; and
2. project-owned fail-closed Kotlin transport contracts and Android networking boundaries.

## Debug native build

An ordinary `:app:assembleDebug` with exact toolchain variables:

1. verifies the clean gitlink, commit/tree, license hash, Go/module pins, Go 1.25.5, and NDK 28.2.13676358 API 26 AArch64 clang;
2. builds official source with `GOOS=android`, `GOARCH=arm64`, `CGO_ENABLED=1`, and `-buildmode=c-shared`;
3. compiles [`jni/podroid_tailscale_jni.c`](jni/podroid_tailscale_jni.c) as a linkage probe;
4. registers both generated libraries only with the debug source set; and
5. verifies final-APK ABI, AArch64 ELF identity, 16 KiB `PT_LOAD` alignment, `DT_NEEDED`, provenance hashes, and manifest policy.

```bash
PODROID_GO=/absolute/path/to/go1.25.5/bin/go \
PODROID_ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358 \
  ./gradlew :app:assembleDebug
```

Release packaging remains disabled, generated binaries are not source-controlled, and the probe is not loaded by production Kotlin code.

## Fail-closed runtime boundary

The app module now contains:

- bounded lifecycle/configure/listen/accept/dial/read/write/remote-address/loopback contracts with explicit close ownership;
- an unavailable capability report for the current official pin;
- Android default-network generation callbacks, bounded `Network.getAllByName`, and per-socket `Network.bindSocket` adapters;
- strict atomic Host-only state under `files/host-transport`, outside `files/instances` and excluded from backup/device transfer;
- generation-based lifecycle/recovery modeling; and
- strict peer evidence parsing with pre-payload deny-all admission.

The current public API has no seam proving its internally created control, DERP, DNS, and peer sockets use those Android hooks. It also lacks deterministic cancellation and authenticated per-connection node/user identity. Therefore the provider reports unavailable, has no runtime service or management composition, and permits no remote Host mutation. There is no `VpnService`, process-wide network binding, VM/QMP/shell/filesystem/forwarding dependency, or embedded Headscale.

Static packaging is proven automatically. Physical Android loading, real `ConnectivityManager` behavior, Tailscale/Headscale enrollment, listen/dial connectivity, peer identity, process restart, reboot recovery, handoff, VPN coexistence, and simultaneous distinct Host/guest identities remain explicitly deferred.
