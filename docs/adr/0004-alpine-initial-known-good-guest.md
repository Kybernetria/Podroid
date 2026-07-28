# ADR 0004: Alpine Is the Initial Known-Good Guest

- **Status:** Accepted

## Context

Guest support requires an end-to-end tested combination of image construction, kernel expectations, init, storage, networking, management, and workload runtime.

## Decision

Alpine is the only initial known-good guest. Documentation and UI must not present another profile as supported until it meets the same acceptance evidence.

## Consequences

MVP validation stays bounded and can reuse inherited Alpine knowledge. The architecture remains open to other distributions through profiles, but placeholders such as `debian-cloud` communicate direction rather than support.

## Alternatives considered

Shipping several nominally supported distributions at launch was rejected because breadth would dilute boot, upgrade, networking, and recovery evidence.
