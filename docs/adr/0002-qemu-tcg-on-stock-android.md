# ADR 0002: QEMU/TCG on Stock Android

- **Status:** Accepted

## Context

The MVP must run on ordinary supported phones without root, a custom OS, or device-specific virtualization privileges.

## Decision

Use QEMU with TCG as the MVP VM execution baseline on stock Android. Android owns the QEMU process and its lifecycle. Hardware-assisted and patched-device paths may coexist or arrive later but are not MVP requirements.

## Consequences

Compatibility is prioritized over native-speed execution. Performance and thermal limits must be measured in the device matrix. The APK must not be described as an Android OS or require a custom ROM.

## Alternatives considered

Requiring AVF/pKVM, root, or a patched Android image was rejected as an MVP baseline because availability is device- and deployment-specific.
