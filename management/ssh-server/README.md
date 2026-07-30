# Restricted SSH Server

No SSH server, listener, SSH dependency, or runtime hookup is implemented or enabled.

The normative policy is in [`../protocol/v1.md`](../protocol/v1.md): Ed25519 user certificates from an enrolled CA, exactly one role principal, revocation and authenticated transport-identity binding, exact exec command `podroid-management-v1`, deny-by-default channels, and only one virtual nested guest-SSH target for the dedicated role.

The pure Kotlin certificate/channel policy does not verify signatures and cannot open a channel. A future SSH provider must prove all capabilities in `ManagementCompositionGate`, pass a separate security review, and add bounded lifecycle composition before this directory can contain a runtime server.
