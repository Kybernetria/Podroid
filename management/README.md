# Management

Owns the versioned restricted Android Host-management protocol and its prospective SSH exposure. It may eventually request authorized lifecycle effects from the authoritative Android VM owner, but it provides no general shell and owns no guest workload state.

Issue #16 adds the normative [v1 contract](protocol/v1.md) and pure Kotlin parsing/policy/ledger/audit models. The slice is disabled by construction: there is no SSH library, listener, runtime composition, dependency-injection binding, Binder/VM adapter, or effect dispatch. The current transport provider is unavailable and the composition gate can only return blockers.

Device-local trust, revocation, idempotency, and audit state is assigned to `files/host-management/` and excluded from backup and device transfer. No production store is implemented in this slice.
