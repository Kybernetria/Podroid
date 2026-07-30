# Transport

Owns authenticated connection seams and provider adapters. Transport supplies bounded connectivity and peer identity evidence; it does not define management commands, authorize VM effects, or schedule workloads.

Issue #15 now has Kotlin contracts/hooks in the existing Android app module and a verified debug-only ARM64 package of the unmodified official source. The provider remains unavailable: its current API cannot accept the Android per-network socket/DNS/default-network hooks, does not prove deterministic cancellation, and exposes remote addresses rather than authenticated per-connection peer identity. The production admission policy is deny-all, and there is no runtime management hookup.

See [`api/README.md`](api/README.md) and [`tailscale-android/README.md`](tailscale-android/README.md).
