# Android Host, Guest, and Controller Boundaries

The permanent product boundary is:

```text
Android Host APK
├── device identity
├── lifecycle supervision
├── bootstrap transport
├── restricted management SSH
├── VM installation and lifecycle
└── power/thermal management

Linux Guest
├── ordinary Linux distribution
├── SSH
├── guest mesh client
├── Docker/K3s/Nomad
└── workloads

Controller
├── node discovery
├── host management
├── guest provisioning
└── existing administration tools
```

“Android Host APK” in this diagram means the Host APK installed on the Android host.

## Android host and Host APK

The Android host provides Android platform facilities. The Host APK and its Host supervisor own:

- Android lifecycle, permissions, foreground-service obligations, notifications, process cleanup, power, and thermal policy;
- serialization and enforcement of the one-active-VM-per-phone invariant;
- QEMU/TCG process launch, monitoring, bounded shutdown, QMP access, host-side storage attachment, and profile selection;
- authoritative host configuration and observed VM lifecycle state;
- the restricted management SSH server and management-protocol authorization;
- the bootstrap transport using official Tailscale/libtailscale and Android network hooks; and
- transport selection behind the transport API.

The Host APK does **not** own Linux package state, containers, Swarm membership or scheduling, guest `tailscaled`, or workload desired state. It does not expose a general Android shell. Existing implementation remains under `app/src/main/java/com/excp/podroid/`, especially `engine/` and `service/`, until later migrations preserve these contracts explicitly.

## Linux guest

The guest owns:

- its userspace, init system, package database, credentials, and filesystems within attached guest storage;
- its independent guest overlay, initially ordinary upstream Linux `tailscaled`, and workload-plane identity/connectivity;
- its orchestrator, initially Docker Engine and Docker Swarm, including membership, workload scheduling, containers, and workload health; and
- distro-specific realization of a distro-neutral profile contract.

The guest consumes virtual hardware and narrow host services. Guest claims are untrusted at the Android boundary. Guest services cannot grant Android permissions, mutate host authority, start a second VM, or turn workload state into host lifecycle truth.

Alpine is the only initial known-good guest. The profile abstraction must not imply that Debian or another profile is supported before its acceptance tests pass.

## External controller

The controller owns:

- operator interaction, local presentation, and explicit management requests;
- `phonectl` command semantics and the Slint desktop experience; and
- controller-side storage of user-approved connection material.

For Android-host lifecycle and management, the controller communicates only through the versioned restricted management protocol over an authenticated transport. It cannot invoke arbitrary Android-host commands, reach QMP directly, write Android persistence directly, or schedule containers. Requests remain subject to host authorization and lifecycle serialization.

For explicit Linux-guest provisioning and diagnostics, the controller may connect directly to the guest's SSH service or a fixed scoped phone endpoint. That path uses an independently approved guest host key and guest-only private key. The SSH transport cannot request forwarding, a PTY, Android shell/filesystem access, or QMP. MVP command execution authenticates as guest root, so it carries the same guest authority as the local root console, including access to the existing bounded guest-to-Android bridge operations (`podroid-forward`, `podroid-power`, `podroid-open`, notifications, and server mode). This does not create an Android shell or arbitrary Android filesystem/QMP authority, but the guest credential is privileged and must be protected accordingly. Guest and Android-host endpoints, host keys, client keys, authorization, and persisted connection state must never be shared.

The controller may be offline after setup. The Android host continues VM lifecycle duties, and the guest continues networking and Swarm workloads, without controller heartbeats or leases. On reconnection, the controller reads authoritative current state rather than replaying assumptions.

## Cross-boundary rules

1. **Management and workload planes stay separate.** Android-side Tailscale/libtailscale bootstraps host management reachability; guest Linux `tailscaled` carries workload and Swarm traffic.
2. **Authority follows ownership.** Host lifecycle state comes from the host; guest workload state comes from guest services; presentation state in a controller is derived.
3. **Protocol before effect.** External input is parsed into a bounded, versioned management request, authenticated, authorized, and then dispatched to the owning component.
4. **No hidden transport authority.** Every transport implements the same connection interface and cannot expand management commands or privileges.
5. **One active VM is atomic.** Start/stop transitions must eventually be serialized by the Android VM service; controllers and guests cannot race around that policy.
6. **Offline is normal.** Loss of a controller does not stop the VM or workloads. Loss of management transport affects manageability, not guest workload ownership.
7. **Controller trust domains stay separate.** Direct guest SSH authorizes guest-only operations; the restricted host-management endpoint authorizes only its versioned host protocol. Credentials and connection state are never reused across them.

## Direction of dependencies

```text
controller UI/CLI -> controller core -> management protocol -> transport API
                         |                                   |
                         |                                   v
                         |                         Android VM service -> lifecycle/model
                         |                                   |
                         |                                   v
                         |                            QEMU/AVF/storage
                         |                                   |
                         | direct verified guest SSH         v
                         +-------------------------> Linux guest profile
                                                    + guest workload plane
```

The diagram is a dependency and authority guide, not a statement that Milestone 1 code exists.
