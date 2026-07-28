# Controller Core

Owns future shared client protocol, connection, retry, idempotency, and state-refresh logic used by all controller front ends. It treats host responses as boundary data and does not cache presentation as authoritative state.

It has no direct QMP or guest scheduler access.
