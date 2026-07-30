# Third-party source pins

## libtailscale

[`libtailscale/`](libtailscale/) is the unmodified official [`tailscale/libtailscale`](https://github.com/tailscale/libtailscale) repository pinned as a Git submodule. Clone it with:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  git submodule update --init --recursive
'
```

[`libtailscale-pin.json`](libtailscale-pin.json) is the reviewed source, license, module, toolchain, ABI, and packaging contract. Debug builds compile that exact tree as an Android `arm64-v8a` Go `c-shared` library with Go 1.25.5, NDK 28.2.13676358's API 26 AArch64 clang, and 16 KiB `PT_LOAD` alignment. The build also links Podroid's project-owned JNI shim against the official C API. Both libraries and their generated provenance stay under `app/build/generated/libtailscale/debug`; generated binaries are never committed.

Supply the exact toolchains and run the ordinary debug build:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  export PODROID_GO=/absolute/path/to/go1.25.5/bin/go
  export PODROID_ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358
  ./gradlew clean assembleDebug
'
```

The build fails closed if the parent gitlink, submodule commit/tree/cleanliness, license hash, `go.mod` versions, Go executable, NDK revision, compiler target, or manifest policy differs. After packaging, a static verifier checks every APK native library is AArch64 under only `arm64-v8a` and has 16 KiB-compatible `PT_LOAD` segments. It also checks the official/shim `DT_NEEDED` contracts, artifact hashes and provenance, and absence of `VpnService` manifest declarations.

This packaging is intentionally **debug-only**. Release source sets and release task wiring do not include these generated files.

The pinned public C API wraps `tsnet` and provides lifecycle, `listen`, `dial`, `ControlURL`, auth-key, state-directory, and remote-address operations. It does **not** itself provide Android `ConnectivityManager`/DNS/active-network hooks or authenticated peer identity. Those runtime requirements remain to be implemented and verified behind `transport/api` before remote host mutations are enabled. The guest continues to use a separate ordinary Linux `tailscaled` identity.
