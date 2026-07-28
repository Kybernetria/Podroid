# ADR 0001: Fork Podroid Additively

- **Status:** Accepted

## Context

The repository starts from a working Podroid Android application. A migration that renames packages, relocates runtime code, or rewires the build before boundaries are proven would create avoidable compatibility and upstream-integration risk.

## Decision

Fork Podroid and evolve it additively. During migration, `app/` remains the logical Android application. Preserve package IDs, existing engine and service paths, persistence, and upstream buildability. New conceptual source areas begin as documentation-only skeletons; code moves or Gradle changes require later scoped work.

## Consequences

The repository temporarily contains both inherited paths and future ownership markers. This duplication is intentional and removable. Milestone 1 changes no APK behaviour.

## Alternatives considered

A clean rewrite or immediate module extraction was rejected because either would discard proven behaviour or combine architecture definition with a high-risk migration.
