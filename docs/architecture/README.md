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

Tickets #10–#11 add the separate strict Host-supervisor desired-state record, atomic v1→v2→v3 migration, versioned possible-live evidence, durable bounded alarm retries, post-unlock boot and sticky process-crash triggers, and authenticated fixed-runtime probes behind `VmManager`. Android force-stop remains authoritative; launcher creation does not recover a VM, and the user must explicitly tap Start VM.

Ticket #9 adds a separate Rust/Slint desktop prototype under `controller/`. Its only composed service remains conspicuously non-live and in-memory; Start and Stop affect preview state only. Ticket #16 freezes and tests the restricted Host-management v1 codec, authorization, idempotency, audit, and channel policy, but supplies no SSH library, listener, or runtime composition because required transport capabilities remain unavailable.
