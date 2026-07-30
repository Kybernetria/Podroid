# Controller core

This crate owns constrained controller-side DTOs, lifecycle action policy, and the complete service authority exposed to front ends:

```text
VmServiceBoundary::{refresh, start, stop}
```

`PreviewVmService` remains a process-local simulation with one validated host identity and exactly `VmId::Default`. It is bounded and non-live.

## Frozen host-management v1 codec

`host_management` is pure protocol and mapping policy. It does not open a socket, spawn SSH, store credentials, or connect to a phone. A future authenticated Android-host transport may implement the narrow `HostManagementExchange` seam; no implementation is supplied here. `HostManagementVmService` maps that seam to `VmServiceBoundary`, retaining only the last authoritative generation.

The frozen v1 wire contract is:

- restricted exec command: exactly `podroid-management-v1`;
- one frame in each direction: unsigned 32-bit big-endian JSON byte length followed by exactly that many bytes and EOF;
- request payload cap: 4096 bytes; response payload cap: 16384 bytes; announced lengths are checked before payload allocation, and the abstract exchange receives the fixed five-second request deadline;
- UTF-8 JSON objects with unsigned integer numbers only; malformed UTF-8, floats, negative numbers, duplicate/unknown/missing fields, excessive nesting, and bytes after the root object or declared frame are rejected;
- `version` is the integer `1` and `request_id` is a canonical lowercase UUIDv4;
- the complete operation allowlist, in protocol-description order, is `protocol.describe`, `vm.default.status`, `vm.default.start`, and `vm.default.stop`;
- every request has exactly `version`, `request_id`, `operation`, and `parameters`; read parameters are empty, while `if_generation` is the mutation parameters object's sole mandatory non-negative int64 field; a successful start response advances the runtime generation by one and a successful stop preserves it;
- VM identity is always `default`; lifecycle/backend/boot-stage combinations, running-only uptime through 315,360,000 seconds, and error-only 1..256-byte control-free diagnostics are parsed through the constrained `VmStatus` model;
- a mutation consumes the cached authoritative generation before exchange and only a validated success restores it, so any failed or uncertain mutation requires a fresh status before another mutation; and
- failures contain only a stable code and its required `retryable` boolean. `busy`, `timeout`, `audit_unavailable`, `capacity_exceeded`, `provider_unavailable`, `interrupted`, and `internal_error` are retryable; `invalid_request`, `unsupported_version`, `unknown_operation`, `unauthenticated`, `forbidden`, `generation_mismatch`, `conflict`, and `indeterminate` are not. A mismatched pair is invalid.

`protocol.describe` returns and verifies the command literal, ordered allowlist, and both frame caps. There is no arbitrary command, shell, file, QMP, forwarding, workload, or scheduler field in any host-management v1 type.

## Credential separation

`guest_ssh` is a separate bounded guest-SSH adapter. Its target, private key, known-host material, enrollment key, and command types cannot be supplied to host-management v1. A future `HostManagementExchange` must use a distinct Android-host endpoint, host key, client key, authorization record, and persisted connection state.

## Locked verification

From the repository root, run the distrobox build wrapper. Every Cargo operation in it uses the committed lockfile:

```sh
./controller/scripts/build-in-android-dev.sh
```
