# ADR 0011: Termux Integration Is Optional

- **Status:** Accepted

## Context

Inherited terminal components are useful for local interaction, but remote management, VM lifecycle, and workload operation must not depend on an interactive terminal.

## Decision

Keep Termux integration optional. Core boot, lifecycle, management, networking, and orchestration contracts cannot depend on Termux being installed, enabled, or open.

## Consequences

Termux adapters remain outside core ownership and degrade independently. Existing vendored terminal code is not removed by this decision. Later extraction must preserve inherited behaviour unless a separate product decision changes it.

## Alternatives considered

Making Termux the management API or a mandatory runtime was rejected because it would couple automation and unattended operation to an interactive UI subsystem.
