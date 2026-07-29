# Persisted Host supervisor state (ticket #10)

The Android Host owns one app-private `host_supervisor_state` Preferences DataStore. It is separate from UI/settings preferences and contains one strict, atomically replaced encoded record.

Schema v1 records:

- Host enabled/disabled;
- desired state for the single default VM (`RUNNING` or `STOPPED`);
- autostart, WakeLock, power, and thermal policies as closed enums;
- a monotonic runtime generation; and
- only the latest bounded lifecycle transaction: monotonic id, closed operation/outcome enums, request/completion timestamps, and a stable redacted error code.

No credential, exception text, path, or arbitrary message is part of the model or codec. A missing v0 record is explicitly initialized to fail-safe v1 defaults (Host disabled, VM stopped, autostart disabled). Unknown future schemas and malformed records fail closed; the repository has no replacement corruption handler and does not rewrite that evidence.

`VmManager` writes the complete desired state and `PENDING` transaction before entering installation or VM backend effects, then writes `SUCCEEDED`/`FAILED` after the effect (and, for stop/remove, authoritative quiescence). Successful accepted launch/restart effects advance the runtime generation. Atomic transforms and transaction tokens prevent stale or duplicate completions from replacing a newer transaction or reducing a generation. DataStore admission/commit is bounded by a five-second deadline, and a timeout prevents the lifecycle effect from starting.

The bounded DTO is readable through `VmManager`, the same-UID local Binder endpoint, and `VmServiceClient`. Ticket #10 intentionally adds no boot receiver, desired-state reconciliation loop, remote protocol, or UI policy editor; those consumers may be added by later tickets without changing persistence ownership.
