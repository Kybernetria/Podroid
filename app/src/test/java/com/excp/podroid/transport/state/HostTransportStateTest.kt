package com.excp.podroid.transport.state

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTransportStateTest {
    @Test
    fun `host state and libtailscale identity paths are outside VM instances`() {
        val files = File("/data/user/0/com.excp.podroid/files")
        val paths = HostTransportPaths(files)
        assertEquals("host-transport", paths.root.fileName.toString())
        assertEquals(paths.root, paths.supervisorStateFile.parent)
        assertEquals(paths.root, paths.libtailscaleStateDirectory.parent)
        assertFalse(paths.root.startsWith(files.toPath().resolve("instances")))
        assertNotEquals(
            paths.libtailscaleStateDirectory.toString(),
            "/var/lib/tailscale",
        )
    }

    @Test
    fun `strict v1 codec round trips bounded lifecycle ownership`() {
        val state = HostTransportPersistentState.safeDefaults().copy(
            desiredEnabled = true,
            desiredGeneration = 4,
            appliedGeneration = 4,
            phase = HostTransportPhase.RUNNING,
            ownerProcess = "process-2",
            ownerGeneration = 4,
        )
        assertEquals(state, HostTransportStateCodec.decode(HostTransportStateCodec.encode(state)))
    }

    @Test
    fun `future corrupt oversized and cross-field invalid records fail closed`() {
        val valid = HostTransportStateCodec.encode(HostTransportPersistentState.safeDefaults())
        val records = listOf(
            valid.toString(Charsets.UTF_8).replace("schema=1", "schema=2").toByteArray(),
            valid.toString(Charsets.UTF_8).replace("phase=STOPPED", "phase=UNKNOWN").toByteArray(),
            valid.toString(Charsets.UTF_8).replace("owner_process=-", "owner_process=p1").toByteArray(),
            ByteArray(HostTransportStateCodec.MAX_ENCODED_BYTES + 1),
            valid + byteArrayOf(0),
        )
        assertTrue(runCatching { HostTransportStateCodec.decode(records[0]) }.exceptionOrNull()
            is HostTransportStateSchemaException)
        records.drop(1).forEach {
            assertTrue(runCatching { HostTransportStateCodec.decode(it) }.exceptionOrNull()
                is HostTransportStateCorruptionException)
        }
    }

    @Test
    fun `atomic file store initializes once persists and refuses corrupt replacement`() {
        val directory = Files.createTempDirectory("podroid-host-transport-test")
        try {
            val paths = HostTransportPaths(directory.toFile())
            val stateFile = paths.supervisorStateFile
            val store = AtomicFileHostTransportStateStore(paths)
            assertEquals(HostTransportPersistentState.safeDefaults(), store.read())

            val committed = store.update { current -> current.copy(
                desiredEnabled = true,
                desiredGeneration = 1,
            ) }
            assertEquals(committed, AtomicFileHostTransportStateStore(paths).read())

            val future = Files.readAllBytes(stateFile).toString(Charsets.UTF_8)
                .replace("schema=1", "schema=7")
            Files.write(stateFile, future.toByteArray())
            val failure = runCatching { store.update { it } }.exceptionOrNull()
            assertTrue(failure is HostTransportStateSchemaException)
            assertEquals(future, Files.readAllBytes(stateFile).toString(Charsets.UTF_8))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomic file store rejects symlink state instead of following it`() {
        val directory = Files.createTempDirectory("podroid-host-transport-link-test")
        try {
            val paths = HostTransportPaths(directory.toFile())
            val stateDirectory = Files.createDirectories(paths.root)
            val target = directory.resolve("outside")
            Files.write(target, HostTransportStateCodec.encode(HostTransportPersistentState.safeDefaults()))
            val link = stateDirectory.resolve("supervisor.state")
            Files.createSymbolicLink(link, target)

            val failure = runCatching { AtomicFileHostTransportStateStore(paths).read() }.exceptionOrNull()
            assertTrue(failure is HostTransportStateCorruptionException)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomic file store rejects symlink Host transport directory`() {
        val directory = Files.createTempDirectory("podroid-host-transport-directory-link-test")
        try {
            val paths = HostTransportPaths(directory.toFile())
            val outside = Files.createDirectory(directory.resolve("outside"))
            Files.write(
                outside.resolve("supervisor.state"),
                HostTransportStateCodec.encode(HostTransportPersistentState.safeDefaults()),
            )
            Files.createSymbolicLink(paths.root, outside)

            val failure = runCatching { AtomicFileHostTransportStateStore(paths).read() }.exceptionOrNull()
            assertTrue(failure is HostTransportStateCorruptionException)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
