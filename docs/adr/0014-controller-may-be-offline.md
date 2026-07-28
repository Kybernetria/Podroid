# ADR 0014: Controller May Be Offline After Setup

- **Status:** Accepted

## Context

Phones and management workstations are intermittently connected. Guest workloads must not depend on a UI process or controller heartbeat.

## Decision

After setup, controllers may be offline indefinitely. The Android host remains authoritative for local VM lifecycle and the guest remains authoritative for networking, Swarm, and workloads. No controller lease is required to keep them running.

## Consequences

Reconnect flows must query current authoritative state and tolerate missed events. Controller commands need explicit duplicate and stale-state handling when implementation begins. Management unavailability degrades administration, not workload ownership.

## Alternatives considered

Requiring an always-on controller was rejected because it creates a remote single point of failure and incorrectly transfers runtime authority out of the phone and guest.
