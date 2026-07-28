# QMP Adapter

Owns future bounded QEMU Machine Protocol sessions, command/response correlation, timeouts, and cleanup. QMP is host-private and must never be exposed directly to controllers or guests.

Current implementation remains in `app/.../engine/QmpClient.kt`.
