# ADR 0007: Ordinary Linux tailscaled for Guest Workloads

- **Status:** Accepted

## Context

Containers and Swarm nodes need Linux-native workload connectivity. Routing this plane through Android would blur ownership and couple workload health to host management plumbing.

## Decision

Run ordinary upstream Linux `tailscaled` inside the guest for workload networking. The guest owns its workload-plane node identity, daemon lifecycle, routes, and failure recovery.

## Consequences

The phone can have separate host-management and guest-workload network identities. Credentials must not be shared implicitly across that boundary. A guest networking failure affects workloads but does not transfer scheduling authority to Android.

## Alternatives considered

Proxying all workload traffic through the Android-side bootstrap was rejected because it combines trust domains and makes the APK part of the workload data plane.
