# Persisted Host supervisor state (ticket #10)

The Android Host owns one app-private `host_supervisor_state` Preferences DataStore. It is separate from UI/settings preferences and contains one strict, atomically replaced encoded record. The DataStore file is explicitly excluded from legacy backup, cloud backup, and device transfer because runtime intent is device-local.

Schema v1 records:

- Host enabled/disabled;
- desired state for the single default VM (`RUNNING` or `STOPPED`);
- autostart, WakeLock, power, and thermal policies as closed enums;
- a monotonic runtime generation; and
- only the latest bounded lifecycle transaction: monotonic id, closed operation/outcome enums, a durable effect-started claim bit, request/completion timestamps, and a stable redacted error code.

No credential, exception text, path, or arbitrary message is part of the model or codec. A missing v0 record is explicitly initialized to fail-safe v1 defaults (Host disabled, VM stopped, autostart disabled). Unknown future schemas and malformed records fail closed; the repository has no replacement corruption handler and does not rewrite that evidence.

Lifecycle mutation uses a durable prepared-command model. `VmManager.prepareLifecycleCommand` atomically writes desired state and one `PENDING` transaction before service Intent enqueue, launch cancellation, installation/removal, or backend effects. The transaction id is also the `ServiceCommandOrder` generation. Explicit stale generations are rejected before mutation. Intents carry only bounded primitive token fields (positive id, closed operation, and non-negative generation base), allowing a recreated process to reconstruct the capability. Acceptance atomically sets the transaction's effect-started claim while leaving it `PENDING`; duplicate delivery, process recreation, or uncertain completion therefore cannot replay an already-claimed effect. Superseded tokens cannot claim effects or complete a newer command.

Restart is one transaction from admission through replacement acceptance. Its desired state remains `RUNNING` and outcome remains `PENDING` while shutdown is in flight. The same token is revalidated before replacement launch, then becomes `SUCCEEDED` and advances runtime generation exactly once only after launch acceptance. There is no intermediate restart success or second phase token.

Binder lifecycle calls await durable admission under one suspend ordering gate before dispatch. Notification, task-removal, and guest-power issuers enter that same path before touching runtime state. The in-memory service queue coordinates start-after-stop execution only; a process crash deliberately leaves the authoritative `RUNNING/PENDING` command for ticket #11 reconciliation.

DataStore admission/commit is bounded by a five-second deadline, and a timeout prevents the lifecycle effect from starting. The bounded DTO is readable through `VmManager`, the same-UID local Binder endpoint, and `VmServiceClient`. Ticket #10 intentionally adds no boot receiver, desired-state reconciliation loop, remote protocol, or UI policy editor.
