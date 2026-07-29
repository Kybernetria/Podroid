# Controller core

This crate owns constrained controller-side DTOs, lifecycle action policy, and the complete service authority exposed to front ends:

```text
VmServiceBoundary::{refresh, start, stop}
```

The only ticket #9 implementation is `PreviewVmService`, a process-local simulation with one validated host identity and exactly `VmId::Default`. It is bounded and non-live. It is not management protocol, transport, persistence, QMP, shell, or scheduler code.

A future ticket #16 client may implement this boundary using the restricted authenticated management protocol. Responses must be parsed into these constrained values before presentation.
