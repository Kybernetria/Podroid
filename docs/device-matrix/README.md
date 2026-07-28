# Device Matrix

This area will hold reproducible compatibility evidence. Milestone 1 records the dimensions only; it makes no new device-support claim.

For each tested device, record:

- manufacturer, model, SoC, ABI, RAM, and Android build/security patch level;
- stock or patched deployment (stock is the MVP baseline);
- install, permission, foreground-service, background, reboot, and battery-policy behaviour;
- QEMU/TCG boot success, boot duration, memory pressure, thermal behaviour, and clean shutdown;
- Android Tailscale/libtailscale hook behaviour and Tailscale or Headscale coordination result;
- guest `tailscaled` connectivity, route isolation, reconnect, and credential separation;
- restricted management protocol enrollment, reconnect, timeout, and authorization failures;
- Alpine profile version, storage migration/recovery, Docker Engine, and Swarm results; and
- evidence links, test date, tester, and known limitations.

Patched-device results must identify `my-avbroot-setup` inputs and remain separate from stock-Android MVP claims.
