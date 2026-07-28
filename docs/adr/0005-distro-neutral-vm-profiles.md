# ADR 0005: Distro-Neutral VM Profiles

- **Status:** Accepted

## Context

VM lifecycle and QEMU concerns are mostly distribution-independent, while images, boot inputs, init integration, and capabilities vary by guest.

## Decision

Define a versioned, distro-neutral VM profile abstraction for guest artifacts, boot inputs, declared capabilities, and lifecycle hooks. Core VM logic consumes constrained profile values and does not branch on distribution names. Distribution-specific realization remains under `profiles/`.

## Consequences

Alpine can be known-good without hard-coding Alpine into the VM domain. Schemas require compatibility and input bounds. The abstraction does not promise that every profile is supported and does not move guest package or workload ownership to Android.

## Alternatives considered

Hard-coding Alpine in core lifecycle code was rejected because it would make a second guest an invasive rewrite. A plugin runtime was rejected as premature.
