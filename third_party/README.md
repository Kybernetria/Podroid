# Third-party source pins

## libtailscale

[`libtailscale/`](libtailscale/) is the unmodified official [`tailscale/libtailscale`](https://github.com/tailscale/libtailscale) repository pinned as a Git submodule. Clone it with:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  git submodule update --init --recursive
'
```

[`libtailscale-pin.json`](libtailscale-pin.json) is the reviewed source, license, module, toolchain, ABI, packaging, and missing-capability contract. Debug builds compile that exact tree as an Android `arm64-v8a` Go `c-shared` library with Go 1.25.5, NDK 28.2.13676358's API 26 AArch64 clang, and 16 KiB `PT_LOAD` alignment. The build also links Podroid's project-owned JNI probe against the official C API. Generated binaries and provenance stay under `app/build/generated/libtailscale/debug` and are never committed.

Supply the exact toolchains and run the ordinary debug build:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  export PODROID_GO=/absolute/path/to/go1.25.5/bin/go
  export PODROID_ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358
  ./gradlew clean assembleDebug
'
```

The build fails closed if the gitlink, submodule commit/tree/cleanliness, license hash, `go.mod` versions, Go executable, NDK revision, compiler target, or manifest policy differs. Final-APK verification checks the sole ABI, AArch64 identity, 16 KiB alignment, `DT_NEEDED`, artifact hashes/provenance, and absence of `VpnService`. Packaging is debug-only; release variants exclude these artifacts.

The public C API provides lifecycle, `listen`, `accept`, `dial`, loopback credentials, `ControlURL`, auth key, state directory, and remote address. It does **not** expose injection seams for Android default-network events, per-`Network` DNS/socket selection, deterministic cancellation, or authenticated per-connection node/user identity. Project-owned Kotlin contracts and Android hook boundaries exist, but the provider remains unavailable and deny-all because adjacent hooks do not prove internal libtailscale sockets use them. Physical loading, networking, identity, lifecycle, and reboot tests remain deferred. The Host identity remains separate from ordinary guest Linux `tailscaled` state.
