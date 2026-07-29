# VM Model

Owns constrained, distro-neutral domain values for VM identity, profile selection, resources, and observed state. Raw external requests and profile documents must be parsed before entering this area.

It owns no Android or guest side effects.

Ticket #6 is implemented incrementally in the authoritative Android module at
`app/src/main/java/com/excp/podroid/vm/VmId.kt`. The only accepted MVP identity
is the serialized token `default`; other IDs, separators, traversal tokens, and
case variants are rejected before filesystem or engine use. `VmEngine` and
`VmConfig` carry this identity, while one-active-VM coordination remains
unchanged. Moving this code into a separate Gradle module is still deferred.
