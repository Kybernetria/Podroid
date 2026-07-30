# ADR 0006: Tailscale/Headscale Android Management Bootstrap

- **Status:** Accepted

## Context

A controller needs authenticated reachability to a phone across typical mobile networks. A generic desktop daemon or generic gomobile `tsnet` wrapper does not provide Android `ConnectivityManager`, DNS, active-network socket binding, and reboot lifecycle integration.

## Decision

Base the bootstrap transport on the official `tailscale/libtailscale` repository behind the project-owned transport API. Add the Android network hooks needed for connectivity changes, DNS resolution, and binding sockets to the active Android network. Support Headscale `ControlURL`, one-time auth-key registration, persistent host transport state, tailnet listening/dialing, and direct or relay connectivity. Do not require Android `VpnService` and do not require a separate Tailscale APK.

The deployment may use Tailscale or an external Headscale coordination server. The Host APK owns the host identity and network lifecycle; the adapter exposes only the transport API. The guest receives a separate identity through ordinary Linux `tailscaled`.

## Consequences

The current public libtailscale C API must be treated as a source dependency and integration starting point, not proof that Android hooks or authenticated peer identity already exist. Its exact source commit and toolchain must be pinned, and the Android spike must verify lifecycle cancellation, network rebinding, DNS behavior, 16 KiB native alignment, and peer identity before remote mutation is enabled. Coordination credentials and node identity are Host APK secrets. The APK does not implement or embed a Headscale server.

Issue #15 proved debug-only ARM64 packaging and 16 KiB alignment for the unmodified official pin, and added tested Android hook/state/admission boundaries. It also confirmed that the pinned API cannot consume those hooks, does not prove deterministic cancellation, and supplies no authenticated per-connection identity. The provider therefore remains unavailable and deny-all pending an official-capability repin and physical validation; see [`../spikes/libtailscale-android.md`](../spikes/libtailscale-android.md).

## Alternatives considered

A bespoke overlay protocol was rejected due to security and interoperability cost. Running an ordinary Linux daemon as if Android had Linux network semantics was rejected for the host side.
