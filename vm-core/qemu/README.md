# QEMU Adapter

Owns future QEMU/TCG command construction, process execution, deadlines, cancellation, and cleanup behind VM-core interfaces. It accepts parsed configuration and does not authorize controller input.

Current implementation remains in `app/.../engine/QemuEngine.kt`.
