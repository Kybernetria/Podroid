# Termux Integration

Owns the optional Termux companion backend: installation detection, documented external-command integration, OpenSSH and PRoot-Distro setup, and honest capability reporting. It is independent of QEMU and is not a VM, container host, normal Docker daemon, Swarm worker, or K3s agent.

Core VM boot, lifecycle, management, networking, and orchestration must work on a phone without Termux. Existing vendored terminal modules remain in place. This skeleton is not a Gradle module and changes no runtime behavior.
