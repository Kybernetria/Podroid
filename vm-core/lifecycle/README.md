# VM Lifecycle

Owns the future serialized VM state machine, idempotent lifecycle operation semantics, and process cleanup obligations. It enforces one active VM in cooperation with the Android VM service.

It does not own Android UI state or guest workload health.
