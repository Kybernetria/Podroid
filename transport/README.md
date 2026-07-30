# Transport

Owns authenticated connection seams and provider adapters. Transport supplies bounded connectivity and peer identity evidence; it does not define management commands, authorize VM effects, or schedule workloads.

Issue #15 now has a contract-only Kotlin spike in the existing Android app module. The official libtailscale provider remains unavailable: its current pin cannot accept the Android per-network socket/DNS/default-network hooks, does not prove deterministic cancellation, and exposes remote addresses rather than authenticated per-connection peer identity. The production admission policy is deny-all, and there is no runtime management hookup.

See [`api/README.md`](api/README.md) and [`tailscale-android/README.md`](tailscale-android/README.md).
