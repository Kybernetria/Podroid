# ADR 0009: phonectl and Slint Desktop Controller

- **Status:** Accepted

## Context

Operators need both scriptable command-line access and a desktop experience without duplicating protocol, validation, or connection policy.

## Decision

Provide `phonectl` as the CLI controller and a Slint-based **external desktop** controller. For Android-host management, both are thin adapters over shared `controller/core` logic and the same versioned management protocol. Slint is not embedded in or packaged with the Android Host APK.

`phonectl` may additionally expose direct Linux-guest SSH operations from shared core. That guest path is not the host-management protocol: it uses separate endpoints, host keys, client keys, authorization, and connection state, and cannot grant an Android-host shell, arbitrary Android filesystem access, or QMP. MVP SSH commands run as guest root and therefore retain access to the pre-existing bounded guest-to-Android bridge operations, including managed port-forward and VM power requests; this privileged guest authority must be documented rather than confused with the future restricted host-management credential.

Ticket #9 may establish the presentation and core boundary before a remote transport exists. Ticket #16 freezes the restricted Host-management v1 contract and controller codec, but the prototype must remain a conspicuously labeled, bounded in-memory preview until a separately reviewed SSH runtime and capable transport are composed. It must not claim live phone connectivity.

## Consequences

CLI and desktop behaviour remain consistent, and UI code does not acquire direct transport or QMP authority. Slint becomes a desktop presentation choice, not a host runtime dependency. Closing a controller does not stop the host service, VM, or guest workload. Packaging and platform support are later implementation decisions.

## Alternatives considered

Independent client implementations were rejected because they would duplicate security-sensitive protocol handling. An Android-only controller was rejected because remote desktop administration is required.
