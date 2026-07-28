# Terminology

Use these terms consistently in code, documentation, protocols, and UI:

- **Android host**: the physical phone and its Android operating system.
- **Host APK**: the permanent Android application installed on the Android host. The existing `:app` module remains the Host APK during additive migration.
- **Host supervisor**: the long-running background service inside the Host APK that owns host reconciliation and VM lifecycle.
- **Bootstrap transport**: the connection used to manage the Android host. The MVP embeds official Tailscale/libtailscale components with Android network hooks and may use Headscale for coordination.
- **VM**: a QEMU/TCG, or later AVF, Linux virtual machine managed as a named instance. The sole initial instance ID is `default`.
- **Guest**: the ordinary Linux operating system inside a VM. It is outside the Android host trust domain.
- **Guest overlay**: Tailscale, Nebula, NetBird, or another network installed and operated inside the guest. The MVP uses ordinary Linux `tailscaled`.
- **Controller**: a laptop, desktop, SBC, or later another phone used to administer Android hosts. The initial clients are `phonectl` and the Slint desktop panel; no controller must remain online after setup.
- **Orchestrator**: Docker Swarm, K3s, Nomad, or another existing workload manager running inside guests. The Host APK does not schedule workloads.

Supporting terms:

- **Podroid**: the upstream Android VM application from which this repository is forked. Inherited code, package IDs, assets, engine paths, and service paths retain that identity during migration.
- **VM profile**: a distro-neutral, versioned, mechanical description of guest artifacts, boot inputs, resources, required devices, initialization, and health checks. It contains no workload or orchestrator desired state.
- **Known-good guest**: a profile whose complete boot and management path is supported and tested. Alpine is the only initial known-good guest.
- **Management plane**: the restricted, authenticated host-management SSH protocol used by a controller to inspect and operate the Host APK and VM lifecycle.
- **Workload plane**: networking and orchestration inside the guest, including its guest overlay and orchestrator.
- **One active VM**: at most one named VM may be starting, running, stopping, or otherwise holding runtime resources on one Android host at a time.
- **Stock Android**: an unmodified production Android installation on which the Host APK runs without privileged patches.
- **Patched Android**: an explicitly later deployment option produced outside the Host APK through `my-avbroot-setup`; it is not an MVP prerequisite.
