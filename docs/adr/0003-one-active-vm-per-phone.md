# ADR 0003: One Active VM per Phone

- **Status:** Accepted

## Context

VM processes contend for memory, CPU, storage attachments, ports, and Android foreground-service ownership. Concurrent starts would complicate safety and recovery.

## Decision

Permit at most one active VM per phone. “Active” includes transitional states that hold or acquire runtime resources, not only a fully running state. The sole MVP VM is a named instance with ID `default`; fixed global runtime paths are not a permitted new design. The Android VM service is the authority and must eventually serialize lifecycle transitions atomically.

## Consequences

Controllers and guests cannot override this invariant. Duplicate or concurrent start requests must converge on one lifecycle operation rather than launch another process. Supporting multiple saved profiles later does not imply concurrent execution.

## Alternatives considered

Multiple concurrent VMs were rejected for the MVP because they increase resource and lifecycle risk without a required use case.
