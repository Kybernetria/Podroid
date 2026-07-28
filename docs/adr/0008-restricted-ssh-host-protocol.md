# ADR 0008: Restricted SSH Host Protocol

- **Status:** Accepted

## Context

Controllers require a widely supported authenticated transport, but a general SSH shell in the Android application would expose excessive authority and an unstable internal surface.

## Decision

Expose a restricted SSH endpoint on the Android host for a versioned management protocol. Authentication is mandatory. The server accepts only bounded protocol operations mapped to explicit host capabilities; it provides no shell, arbitrary process execution, arbitrary file access, agent forwarding, or unrestricted port forwarding.

## Consequences

`management/protocol` owns request semantics while `management/ssh-server` owns SSH framing, authentication, deadlines, and session bounds. Unknown versions and commands fail closed. Security review and protocol-level audit events are required before implementation.

## Alternatives considered

A general-purpose host shell was rejected under least privilege. A bespoke encrypted transport was rejected because SSH already provides established authentication and channel semantics.
