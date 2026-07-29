# Architecture

Milestone 1 fixes vocabulary, scope, ownership, and repository direction without changing the built application.

Read in this order:

1. [Terminology](terminology.md)
2. [Scope and exclusions](scope.md)
3. [Host, guest, and controller boundaries](boundaries.md)
4. [Repository layout](repository-layout.md)
5. [Persisted Host supervisor state](host-supervisor-state.md)
6. [Accepted ADRs](../adr/README.md)

These documents describe the target MVP architecture. A statement about a target component is not evidence that it has been implemented. The current runtime remains the inherited `app/` implementation until later, separately reviewed milestones wire new areas into the build.

## Current implementation status

Tickets #7 and #8 add the narrow production `VmManager` boundary and same-UID local Binder access over the inherited Android runtime. `PodroidService` remains the owner of Android foreground-service, WakeLock, notification, and host-bridge mechanics.

Tickets #10–#11 add the separate strict Host-supervisor desired-state record, explicit v1→v2 migration, durable lifecycle/reconciliation evidence, post-unlock boot trigger, sticky process-crash recovery, and bounded fixed-runtime probes behind `VmManager`. Android force-stop remains authoritative and suppresses recovery until user relaunch.

Ticket #9 adds a separate Rust/Slint desktop prototype under `controller/`. Its only service implementation is conspicuously non-live and in-memory; Start and Stop affect preview state only. It has no dependency on or authority inside the Android APK. Authenticated phone connectivity remains deferred until the restricted remote-management protocol in ticket #16.
