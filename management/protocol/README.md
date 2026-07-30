# Management Protocol

The normative restricted Host-management v1 contract is [v1.md](v1.md). It fixes framing, exact request/response schemas, errors and retry rules, generations, idempotency, audit, resource limits, and compatibility behavior.

The Kotlin implementation in `app/src/main/java/com/excp/podroid/management/` is pure boundary policy only. Unknown versions, fields, operations, roles, and capabilities fail closed. It excludes arbitrary commands, files, QMP passthrough, workload scheduling, and transport-provider implementation details.
