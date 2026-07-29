package com.excp.podroid.vm

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyVmFilesMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `moves legacy persistent assets logs sockets and qemu data without loss`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        val legacyStorage = filesDir.resolve("storage.img").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        filesDir.resolve("vmlinuz-virt").writeText("kernel")
        filesDir.resolve("initrd.img").writeText("initrd")
        filesDir.resolve("alpine-rootfs.squashfs").writeText("rootfs")
        filesDir.resolve("console.log").writeText("boot log")
        filesDir.resolve("qmp.sock").writeText("stale socket")
        filesDir.resolve("efi-virtio.rom").writeText("efi")
        filesDir.resolve("keymaps").mkdir().also {
            filesDir.resolve("keymaps/en-us").writeText("map")
        }

        LegacyVmFilesMigration(filesDir, paths).migrate()

        assertFalse(legacyStorage.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4), paths.storageImage.readBytes().toList())
        assertEquals("kernel", paths.kernel.readText())
        assertEquals("initrd", paths.initrd.readText())
        assertEquals("rootfs", paths.rootfs.readText())
        assertEquals("boot log", paths.consoleLog.readText())
        assertEquals("stale socket", paths.qmpSocket.readText())
        assertEquals("efi", paths.qemuEfiRom.readText())
        assertEquals("map", paths.qemuKeymapsDirectory.resolve("en-us").readText())
    }

    @Test
    fun `is idempotent after a completed migration`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        filesDir.resolve("storage.img").writeText("persistent")
        val migration = LegacyVmFilesMigration(filesDir, paths)

        migration.migrate()
        migration.migrate()

        assertEquals("persistent", paths.storageImage.readText())
        assertFalse(filesDir.resolve("storage.img").exists())
    }

    @Test
    fun `retries after interruption with some entries already moved`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        paths.instanceDirectory.mkdirs()
        paths.storageImage.writeText("already moved")
        filesDir.resolve("initrd.img").writeText("still legacy")

        LegacyVmFilesMigration(filesDir, paths).migrate()

        assertEquals("already moved", paths.storageImage.readText())
        assertEquals("still legacy", paths.initrd.readText())
        assertFalse(filesDir.resolve("initrd.img").exists())
    }

    @Test
    fun `collision fails before moving any legacy entry and never overwrites`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        paths.instanceDirectory.mkdirs()
        filesDir.resolve("storage.img").writeText("legacy")
        paths.storageImage.writeText("destination")
        filesDir.resolve("initrd.img").writeText("must remain")

        expectIOException { LegacyVmFilesMigration(filesDir, paths).migrate() }

        assertEquals("legacy", filesDir.resolve("storage.img").readText())
        assertEquals("destination", paths.storageImage.readText())
        assertEquals("must remain", filesDir.resolve("initrd.img").readText())
        assertFalse(paths.initrd.exists())
    }

    @Test
    fun `destination created after preflight is never overwritten`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        filesDir.resolve("storage.img").writeText("legacy")
        var injected = false
        val migration = LegacyVmFilesMigration(filesDir, paths) { _, destination ->
            if (!injected) {
                Files.write(destination, "racing destination".toByteArray())
                injected = true
            }
        }

        expectIOException { migration.migrate() }

        assertEquals("legacy", filesDir.resolve("storage.img").readText())
        assertEquals("racing destination", paths.storageImage.readText())
    }

    @Test
    fun `rejects a legacy source symlink without moving other files`() {
        val filesDir = temporaryFolder.newFolder("files")
        val outside = temporaryFolder.newFile("outside").apply { writeText("secret") }
        Files.createSymbolicLink(filesDir.toPath().resolve("storage.img"), outside.toPath())
        filesDir.resolve("initrd.img").writeText("must remain")
        val paths = VmPaths.default(filesDir)

        expectIOException { LegacyVmFilesMigration(filesDir, paths).migrate() }

        assertTrue(Files.isSymbolicLink(filesDir.toPath().resolve("storage.img")))
        assertEquals("secret", outside.readText())
        assertTrue(filesDir.resolve("initrd.img").exists())
        assertFalse(paths.initrd.exists())
    }

    @Test
    fun `rejects symlink in destination hierarchy`() {
        val filesDir = temporaryFolder.newFolder("files")
        val outside = temporaryFolder.newFolder("outside-instance")
        Files.createSymbolicLink(filesDir.toPath().resolve("instances"), outside.toPath())
        filesDir.resolve("storage.img").writeText("persistent")
        val paths = VmPaths.default(filesDir)

        expectIOException { LegacyVmFilesMigration(filesDir, paths).migrate() }

        assertEquals("persistent", filesDir.resolve("storage.img").readText())
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun `rejects nested symlink in legacy qemu directory`() {
        val filesDir = temporaryFolder.newFolder("files")
        val outside = temporaryFolder.newFile("outside-keymap")
        val keymaps = filesDir.resolve("keymaps").apply { mkdir() }
        Files.createSymbolicLink(keymaps.toPath().resolve("escape"), outside.toPath())
        val paths = VmPaths.default(filesDir)

        expectIOException { LegacyVmFilesMigration(filesDir, paths).migrate() }

        assertTrue(Files.isSymbolicLink(keymaps.toPath().resolve("escape")))
        assertFalse(paths.qemuKeymapsDirectory.exists())
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }
}
