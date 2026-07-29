# ADR 0009: phonectl and Slint Desktop Controller

- **Status:** Accepted

## Context

Operators need both scriptable command-line access and a desktop experience without duplicating protocol, validation, or connection policy.

## Decision

Provide `phonectl` as the CLI controller and a Slint-based **external desktop** controller. Both are thin adapters over shared `controller/core` logic and the same versioned management protocol. Slint is not embedded in or packaged with the Android Host APK.

Ticket #9 may establish the presentation and core boundary before a remote transport exists. Until ticket #16 defines and implements restricted host management, that prototype must use a conspicuously labeled, bounded in-memory preview boundary and must not claim live phone connectivity.

## Consequences

CLI and desktop behaviour remain consistent, and UI code does not acquire direct transport or QMP authority. Slint becomes a desktop presentation choice, not a host runtime dependency. Closing a controller does not stop the host service, VM, or guest workload. Packaging and platform support are later implementation decisions.

## Alternatives considered

Independent client implementations were rejected because they would duplicate security-sensitive protocol handling. An Android-only controller was rejected because remote desktop administration is required.
