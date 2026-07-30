# ADR 0007: Ordinary Linux tailscaled for Guest Workloads

- **Status:** Accepted

## Context

Containers and Swarm nodes need Linux-native workload connectivity. Routing this plane through Android would blur ownership and couple workload health to host management plumbing.

## Decision

Run ordinary upstream Linux `tailscaled` inside the guest for workload networking. The guest owns its workload-plane node identity, daemon lifecycle, routes, and failure recovery. Alpine OpenRC starts it after `podroid-network`; a bounded optional reconnect one-shot runs after the daemon, while the Android `Ready!` gate remains independent of enrollment and control-plane reachability.

The Android host and Linux guest use separate node identities. Their auth keys, node keys, state directories, hostnames, and control-plane lifecycle must never be reused or copied across the VM boundary. Guest daemon identity is persisted only by the guest overlay under `/var/lib/tailscale`; the future Android transport owns different host-private state outside the guest disk.

Guest enrollment is an explicit root-only operation through `podroid-tailscale-enroll`. It consumes a one-use key from a mode-0600 temporary file under `/run` (or bounded stdin staged under `/run`), passes only a `file:` reference to `tailscale up`, removes the input and staging file on every exit path, and records only the canonical control URL and guest hostname after success. A changed server requires explicit `--reauth`. Tailscale SSH, accepted/advertised routes, and exit-node behavior are disabled by default.

## Consequences

The phone can have separate host-management and guest-workload network identities. Credentials must not be shared implicitly across that boundary. A guest networking failure affects workloads but does not transfer scheduling authority to Android.

## Alternatives considered

Proxying all workload traffic through the Android-side bootstrap was rejected because it combines trust domains and makes the APK part of the workload data plane.
