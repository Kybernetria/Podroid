# Controller core

This crate owns constrained controller-side DTOs, lifecycle action policy, and the complete service authority exposed to front ends:

```text
VmServiceBoundary::{refresh, start, stop}
```

The only ticket #9 implementation is `PreviewVmService`, a process-local simulation with one validated host identity and exactly `VmId::Default`. It is bounded and non-live. It is not management protocol, transport, persistence, QMP, shell, or scheduler code.

A future ticket #16 client may implement this boundary using the restricted authenticated management protocol. Responses must be parsed into these constrained values before presentation. Protocol decoders must enforce the protocol-defined whole-frame byte cap while accumulating bytes and reject the frame as soon as that cap is exceeded, before UTF-8 decoding or creating any `String`. Per-field DTO limits do not replace that frame cap. After framing and UTF-8 validation, decoders may pass borrowed `&str` fields to the DTO parsers, which validate byte length and permitted characters before retaining their own bounded copies.

Ticket #13 adds a separate bounded guest-SSH adapter. It invokes fixed OpenSSH tools with strict out-of-band host-key verification and exposes direct guest status, intentional guest command execution, and guest Tailscale enrollment. It has no Android-host shell, QMP, or forwarding API. Guest credentials remain a distinct trust domain from future host-management credentials.
