# ADR 0012: Patched Android Comes Later

- **Status:** Accepted

## Context

Some deployments may benefit from platform capabilities unavailable on stock Android, but making those capabilities foundational would contradict the MVP compatibility target.

## Decision

Defer patched-Android support until after the stock-Android MVP. When pursued, device preparation is performed through the external `my-avbroot-setup` path. It remains an optional deployment mode and must not redefine the APK as an Android OS.

## Consequences

MVP requirements cannot assume patched permissions, root, or custom platform services. Later integrations need explicit trust, update, rollback, and device-compatibility analysis.

## Alternatives considered

Requiring patched devices for the MVP was rejected because it narrows deployment and adds an unrelated OS maintenance responsibility.
