# Architecture

Milestone 1 fixes vocabulary, scope, ownership, and repository direction without changing the built application.

Read in this order:

1. [Terminology](terminology.md)
2. [Scope and exclusions](scope.md)
3. [Host, guest, and controller boundaries](boundaries.md)
4. [Repository layout](repository-layout.md)
5. [Accepted ADRs](../adr/README.md)

These documents describe the target MVP architecture. A statement about a target component is not evidence that it has been implemented. The current runtime remains the inherited `app/` implementation until later, separately reviewed milestones wire new areas into the build.

## Current VM management boundary

Ticket #7 adds the first narrow production boundary over that inherited runtime: `VmManager` supports only `VmId.DEFAULT` and owns installation, launch configuration, serialized/idempotent lifecycle mutations, explicit data-removal policy, bounded console reads, typed QMP observations, and fixed SSH endpoint discovery. `PodroidService` remains the owner of Android foreground-service, WakeLock, and notification mechanics. This boundary intentionally does not add Binder, desired-state persistence, or reconciliation.
