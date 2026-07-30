# Documentation

This is the entry point for repository documentation.

- [User guide](guide/index.html) — current inherited application behaviour.
- [Architecture](architecture/README.md) — Milestone 1 terminology, scope, boundaries, and repository map.
- [Architecture decisions](adr/README.md) — accepted decisions and ADR process.
- [Upstream and build baseline](baseline/README.md) — preserved commit, build procedure, inventory, and measurements.
- [First implementation backlog](backlog/first-implementation.md) — ordered GitHub issues and execution rule.
- [Third-party source pins](../third_party/README.md) — official libtailscale source and integration status.
- [Android libtailscale spike](spikes/libtailscale-android.md) — automated evidence, unavailable capabilities, and physical deferrals.
- [Threat model](threat-model/README.md) — security assets, trust boundaries, and deferred analysis.
- [Device matrix](device-matrix/README.md) — compatibility evidence to collect.

The existing `app/` module remains the logical Android application while boundaries are added incrementally. External controller crates live in their own Rust workspace; transport spike sources currently compile inside `app/` and remain disconnected from runtime management effects.
