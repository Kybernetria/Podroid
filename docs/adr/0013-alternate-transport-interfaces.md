# ADR 0013: Alternate Transport Interfaces Now, Implementations Later

- **Status:** Accepted

## Context

Management semantics should not be coupled to one network provider, but speculative transport implementations would increase Milestone 1 scope and security surface.

## Decision

Define ownership and stable seams for transport-neutral connection interfaces now. Keep authentication and management authorization above those seams. Implement the Tailscale Android adapter in its later milestone and defer all alternate transport implementations until a concrete use case is approved.

## Consequences

Management and controller core depend on a narrow transport API rather than provider internals. The interface must carry deadlines, cancellation, peer identity evidence, and bounded streams without granting commands. Empty skeletons are not claims of implementation.

## Alternatives considered

Hard-wiring management to provider APIs was rejected for coupling. Building several transports immediately was rejected as speculative complexity.
