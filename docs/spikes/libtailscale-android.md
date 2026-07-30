# Android-aware libtailscale spike

## Result

The official [`tailscale/libtailscale`](https://github.com/tailscale/libtailscale) source is pinned unmodified at `5e89501def80a6579ca5d0f9a02f336be62b8f2e` (tree `8ec36e63223a51af384482a786f27e4fc53b4458`, `tailscale.com` v1.94.1). The spike proves reproducible debug APK packaging and project boundaries, but the provider remains **unavailable** and remote Host management remains deny-all.

| Requirement | Automated result | Promotion status |
|---|---|---|
| Official source provenance | Commit, tree, clean/ignored state, license hash, Go/module pins, toolchain, and gitlink fail closed | Proven |
| Android ABI packaging | Generated `arm64-v8a/libtailscale.so` and project JNI probe are ELF64 AArch64 and packaged in the debug APK only | Proven statically |
| 16 KiB compatibility | Every generated `PT_LOAD` has 16,384-byte alignment | Proven statically |
| Public lifecycle/listen/dial surface | JNI link probe references configure/start/up/close/getips/listen/accept/remote-address/dial/loopback/error symbols | Proven at link time |
| `ConnectivityManager` changes | Generation-fenced default-network callback exists and owns unregister | Compiles/tests; not injected into libtailscale |
| DNS and active network | Bounded `Network.getAllByName` and per-socket `Network.bindSocket` adapters exist; process-wide binding is forbidden | Compiles/tests; not injected into libtailscale |
| Persistent Host state/recovery | Strict versioned atomic state, Host-only libtailscale path, backup exclusions, generation ownership, and restart reconciliation are tested | Contract proven; native recovery unverified |
| Authenticated peer identity | Remote IP never authorizes; authenticated evidence gate and deny-all production policy are tested | Blocked: current C API has no per-connection identity evidence |
| Tailscale/Headscale tailnet connectivity | C symbols build and link | Real listen/dial/enrollment unverified |
| `VpnService` | Source and final binary manifest reject `VpnService` and `BIND_VPN_SERVICE` | Proven not mandatory |
| Host/guest identity separation | Host paths/types/state are distinct from `files/instances/default` and guest `/var/lib/tailscale` | Proven structurally |

## Why the provider is unavailable

The pinned C API wraps `tsnet`, but its internally created control, DERP, DNS, and peer sockets cannot be supplied with an Android `Network`, resolver, or socket-binding callback. Adding Android hooks beside the library is not proof that the library uses them. The wrapper also uses blocking/background contexts and contains incomplete cancellation/related-handle cleanup. `tailscale_getremoteaddr` returns only an IP locator; it does not return cryptographically bound node, user, or tag identity for the accepted connection.

LocalAPI loopback credentials may support a future bounded WhoIs/status adapter, but mapping an address to LocalAPI metadata must be proven against connection races, revocation, ACL changes, Tailscale, and external Headscale before it can authorize anything. The current admission policy therefore closes every candidate before payload methods are exposed.

## Packaging measurements

The verified debug build produced:

- `libtailscale.so`: 31,150,472 bytes;
- `libpodroid-tailscale-jni.so`: 5,616 bytes; and
- debug APK without the optional generated guest rootfs: 54,106,240 bytes.

These are spike measurements, not release budgets. Release variants contain neither generated library.

## Physically deferred

No attached Android device was available. The following are not claimed:

- Android dynamic-loader and Go runtime startup on API 26+ or a 16 KiB-page device;
- real default-network callbacks, Private DNS, IPv6-only/NAT64, Wi-Fi/cellular handoff, captive portals, metered networks, or external VPN coexistence;
- Tailscale or external Headscale enrollment, direct/DERP connectivity, listen/dial/accept, LocalAPI peer identity, ACL/tag changes, or revocation;
- process kill, force-stop, Doze, foreground/background behavior, reboot/update recovery, identity persistence, or native teardown; and
- simultaneous distinct Host and guest nodes on a physical phone.

Activation requires an official reviewed API/pin with usable Android network-injection and deterministic cancellation seams, plus successful physical validation. Podroid does not patch or fork libtailscale to manufacture those capabilities.
