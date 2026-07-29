# Persisted Host supervisor state (tickets #10–#11)

The Android Host owns one app-private, credential-protected `host_supervisor_state` Preferences DataStore. It is separate from UI/settings preferences, contains one strict atomically replaced record, and is excluded from backup/device transfer.

Schema v2 retains the v1 desired-state and latest lifecycle transaction fields and adds only bounded reconciliation evidence: consecutive attempts (maximum five), next eligible wall-clock time, and closed last-trigger/outcome/error enums. The repository performs exactly one explicit v1-to-v2 migration inside the atomic DataStore update. Missing v0 initializes fail-safe disabled/stopped defaults. Unknown future versions, malformed records, and invalid cross-field combinations fail closed without replacing evidence.

A process death that leaves the latest lifecycle transaction `PENDING` is first resolved as `FAILED/PROCESS_DIED` and reconciliation records `INTERRUPTED`. Only then may reconciliation prepare a fresh `START` transaction through `VmManager`. Successful lifecycle commands reset durable backoff. Failed reconciliation uses bounded exponential delay (5 seconds through a 15-minute cap) and stops after five consecutive attempts. Persisted values contain no exception text, path, credential, or arbitrary message.

Triggers are deliberately distinct:

- `BOOT_COMPLETED` starts the foreground service only for Host enabled + autostart + desired `RUNNING`. Because the DataStore is credential protected, no direct-boot/locked-boot receiver is registered.
- a `START_STICKY` null-intent service recreation and a user app cold start reconcile enabled desired `RUNNING` regardless of autostart;
- explicit desired `STOPPED` never launches.

Android force-stop is respected. Force-stop suppresses manifest receivers and sticky service recreation; Podroid does not attempt to bypass it. Reconciliation resumes only after the user launches the app again.

Before every new manager-owned generation, fixed-name probes inspect both one-active-VM backends. QEMU is identified through its confined QMP endpoint; AVF through the fixed `podroid` framework VM name. A live orphan cannot be safely adopted with complete process ownership/callbacks, so it receives a bounded typed quit/stop and must become quiescent before restart. Probe uncertainty fails closed. Stale Unix endpoints are deleted only after the probe established no controllable runtime and after confinement, NOFOLLOW type, owner, and identity checks. Asset refresh no longer deletes runtime endpoints.

The existing `VmManager` launch-plan path restores persisted and implicit port forwards. A separate `HostTransportReconciler` seam runs after runtime reconciliation; its ticket-#11 production binding is an explicit successful `NO_CONFIGURED_TRANSPORT` no-op until ticket #15.

One process-local reconciler runs at a time. Foreground notification and WakeLock are established before reconciliation work, released when no work/runtime remains, and retained for a live or non-quiescent runtime.
