# ADR 0008: Restricted SSH Host Protocol

- **Status:** Accepted; issue #16 design slice remains disabled

## Context

Controllers require a widely supported authenticated transport, but a general SSH shell in the Android application would expose excessive authority and an unstable internal surface. Host lifecycle authority must remain with the Android VM owner, guest SSH must remain a separate trust domain, replayable mutations need durable uncertainty handling, and no management effect may occur without durable audit evidence.

The official libtailscale spike currently reports its provider unavailable: Android per-network socket/DNS injection, deterministic cancellation, and authenticated per-connection peer identity are not all proven. No reviewed Android SSH provider or production management persistence exists.

## Decision

The normative contract is [`management/protocol/v1.md`](../../management/protocol/v1.md). Its closed v1 surface is:

- SSH username `podroid-management` and exact exec selector `podroid-management-v1`;
- uint32-BE frames bounded to 4,096 request bytes and 16,384 response bytes;
- only `protocol.describe`, `vm.default.status`, `vm.default.start`, and `vm.default.stop`;
- canonical lowercase UUIDv4 request IDs and mandatory `if_generation` on mutations;
- fixed response schemas, error/retry mapping, exit statuses, deadlines, and session/channel limits;
- Ed25519 user certificates signed by an enrolled Ed25519 CA, valid for at most 24 hours, exactly one of `read`, `operate`, or `vm-default-ssh`, no options/extensions, revocation checks, and exact authenticated transport-identity binding;
- denial of shell, PTY, environment, subsystem, X11, agent, global/reverse, unknown, and arbitrary forwarding requests;
- a dedicated `vm-default-ssh` role whose only `direct-tcpip` destination is virtual `vm/default/ssh:22`, internally resolved to fixed `127.0.0.1:9922`, followed by independent nested guest SSH authentication;
- an atomic bounded mutation ledger with `RESERVED`, `EXECUTING`, `COMPLETED`, `REJECTED`, and `INDETERMINATE` states, no concurrent replay, hash-conflict rejection, and fail-closed restart conversion;
- durable redacted pre-dispatch audit as a prerequisite to lifecycle dispatch; and
- app-private `files/host-management/` trust/ledger/audit ownership excluded from Android backup and device transfer.

Issue #16 implements only pure Kotlin values, parsers, policies, store contracts/fakes, tests, documentation, and a static verifier. It introduces no SSH library/listener, Android service or Hilt composition, transport opening, Binder/`VmManager` call, QMP access, process execution, or runtime effect adapter.

`ManagementCompositionGate` evaluates a closed set of required transport, SSH, trust, ledger, and audit capabilities. It always retains `RUNTIME_COMPOSITION_NOT_IMPLEMENTED` and returns no listener or dispatch capability. The unavailable current transport therefore leaves Host management unreachable by construction.

## Consequences

The protocol and authority can be tested before exposing a network surface. Unknown versions, fields, commands, capabilities, channels, and forwarding targets fail closed. Protocol credentials cannot become guest credentials, and direct guest forwarding cannot become arbitrary Host forwarding.

Completed mutation responses can be returned after a lost response; requests that may have crossed the effect boundary become non-replayable and require status reconciliation. Hard ledger/audit limits intentionally deny new work instead of evicting safety evidence. Production persistence, retention/rotation, cryptographic SSH verification, runtime composition, and operational enablement require later reviewed changes.

## Alternatives considered

A general-purpose Host shell was rejected under least privilege. A bespoke encrypted transport was rejected because SSH already provides established authentication and channel semantics. Raw public keys or password authentication were rejected because they cannot encode short-lived scoped roles. SSH certificate authorization without transport identity binding was rejected because CA membership alone does not identify the authenticated transport peer. Arbitrary `direct-tcpip`, reverse forwarding, and sharing guest credentials were rejected as trust-boundary violations. In-memory-only idempotency/audit and automatic ledger eviction were rejected because process death or expiry could replay a mutation. Enabling a listener against the incomplete provider spike was rejected because it would silently weaken identity, network-isolation, deadline, and cancellation guarantees.
