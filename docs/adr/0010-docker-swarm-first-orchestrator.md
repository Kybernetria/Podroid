# ADR 0010: Docker Swarm Is the First Orchestrator

- **Status:** Accepted

## Context

The product needs an initial multi-node container orchestrator. Scheduling logic already belongs to mature guest-side systems and should not be recreated in the APK.

## Decision

Use Docker Swarm as the first orchestrator. Docker Engine and Swarm run inside guests and own membership, desired workload state, placement, reconciliation, and workload health. The APK manages its local VM lifecycle only; it is not a scheduler or orchestrator.

## Consequences

Management operations may request narrowly defined guest bootstrap or report derived status, but cannot become a competing source of workload truth. Swarm traffic uses the guest workload plane. Controller loss does not stop reconciliation.

## Alternatives considered

Implementing fleet or container scheduling in Android was rejected as a boundary violation. Other orchestrators may be evaluated later without changing host lifecycle ownership.
