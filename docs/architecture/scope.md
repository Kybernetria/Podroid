# MVP Scope and Exclusions

## In scope for the target MVP

- A fork of Podroid that preserves upstream buildability while architecture migrates additively.
- QEMU with TCG on stock Android, with one active VM per phone.
- A distro-neutral VM profile contract and Alpine as the sole initial known-good guest.
- Android management bootstrap using official Tailscale/libtailscale components, Android network hooks, and either Tailscale or Headscale coordination.
- Ordinary Linux `tailscaled` in the guest for workload networking.
- A restricted SSH management protocol exposed by the Android host.
- `phonectl` and a Slint desktop controller using the same management contract.
- Docker Swarm as the first orchestrator, owned and executed inside guests.
- Optional Termux integration.
- Transport interfaces that allow later implementations without changing management or VM-domain contracts.
- Operation of a configured phone and its guest when no controller is online.

## Permanent architecture exclusions

These are not alternate interpretations of the MVP:

- The APK is not an Android OS, custom ROM, device fleet scheduler, container scheduler, or Swarm control plane.
- The Android host never becomes the authoritative scheduler for guest workloads; Docker Swarm owns workload placement.
- The host SSH endpoint is not a general Android shell, file server, port-forwarding service, or arbitrary command runner.
- The controller is not a runtime dependency after setup and is not authoritative for live guest state.
- Termux is not required for boot, lifecycle, management, networking, or orchestration.
- A VM profile does not move guest package management or workload execution into Android.
- Headscale, when selected, is an external coordination dependency; this repository does not turn the APK into a Headscale server.
- Alternate transports must not bypass authentication, authorization, the one-active-VM invariant, or the management protocol.

## Explicitly deferred

- Guests other than Alpine becoming known-good.
- Implementations of alternate transports.
- Patched-Android enablement through `my-avbroot-setup`.
- Multiple installed VM definitions, if ever needed. They do not relax the one-active-VM invariant.
- Any feature implementation represented by the Milestone 1 skeleton.

The current AVF and other inherited features remain where they are; this milestone neither removes nor extends them. QEMU/TCG is the fixed MVP baseline for stock Android.
