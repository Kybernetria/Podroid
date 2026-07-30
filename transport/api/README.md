# Transport API

The active Kotlin contracts live under `app/src/main/java/com/excp/podroid/transport/api` while the repository is still a single Android application module. They define bounded TCP listen/dial/accept and I/O requests, monotonic deadlines, cancellation observations, teardown ownership, capability reporting, and a peer-admission boundary.

An inbound candidate has no payload read/write capability. The admission gate must evaluate provider-authenticated identity first and closes a denial before payload bytes are exposed. A remote IP address is explicitly unauthenticated routing metadata. The current production policy is deny-all.

These interfaces carry bytes only. They do not import VM lifecycle, QMP, shell, filesystem/forwarding commands, or management dispatch and grant no Host mutation authority.
