# Threat Model

This placeholder records the required scope for the implementation threat model; it is not a completed security assessment.

## Assets

- Android signing identity, app persistence, VM images, and host configuration
- host and guest Tailscale/Headscale node credentials
- SSH host keys, controller credentials, and peer authorization policy
- guest data, Swarm state, workload secrets, and storage
- lifecycle authority enforcing one active VM

## Trust boundaries

The Android host, Linux guest, external controller, coordination service, and build/update supply chain are separate trust domains. QMP and Android internals are host-private. Guest messages, controller requests, profile files, network peers, and persisted data must be treated as hostile at their receiving boundary.

## Required analysis before implementation

- authenticated enrollment, revocation, key storage, and recovery;
- SSH algorithms, peer identity mapping, command allowlist, rate/session limits, deadlines, and audit events;
- profile schema limits, artifact integrity, path safety, and rollback;
- QMP isolation and prevention of direct controller/guest access;
- host/guest network identity separation and route exposure;
- duplicate, concurrent, stale, and interrupted lifecycle operations;
- controller compromise while the phone continues offline; and
- dependency provenance for Tailscale/libtailscale, Slint, QEMU, libslirp, libusb, guest packages, and build images.

No skeleton endpoint should be enabled until its concrete threat model and tests are reviewed.
