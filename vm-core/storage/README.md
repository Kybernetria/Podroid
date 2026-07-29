# VM Storage

Owns validated instance paths, attachment plans, capacity bounds, integrity checks, and storage cleanup. It does not interpret guest package state or expose arbitrary host paths.

Ticket #6 is implemented incrementally in the authoritative Android module at
`app/src/main/java/com/excp/podroid/vm/`. `VmPaths` confines all boot assets,
persistent storage, console logs, QEMU/AVF sockets, QMP, firmware lookup, and
working directories to `filesDir/instances/default`. Application startup runs a
bounded, idempotent legacy rename migration before extraction or launch. The
migration rejects symlinks and source/destination collisions, never overwrites,
and can continue after interruption. Existing `storage.img` data remains the
authoritative guest overlay after the move. Separate Gradle-module extraction
is still deferred.
