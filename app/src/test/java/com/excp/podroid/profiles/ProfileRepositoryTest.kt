package com.excp.podroid.profiles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val origins = ApprovedArtifactOrigins.of(APPROVED_ORIGIN)
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Ed25519PublicKey.fromX509(keyPair.public.encoded)

    @Test
    fun `prepare streams exact artifacts into fixed CAS paths and fully revalidates dedup`() {
        val artifacts = artifactBytes("first")
        val fetcher = RecordingFetcher(artifacts)
        val root = temporaryFolder.newFolder("repository")
        val repository = repository(root, fetcher)
        val envelope = envelope(generation = 1, artifacts = artifacts)

        val prepared = repository.prepare(envelope)

        assertEquals(3, fetcher.calls.get())
        assertEquals(ProfileGeneration(1), prepared.candidate.generation)
        assertEquals(DataCompatibilityId(COMPATIBILITY), prepared.dataCompatibility)
        prepared.artifactFiles.forEach { (role, file) ->
            assertArrayEquals(artifacts.getValue(role), file.readBytes())
            assertTrue(file.canonicalPath.startsWith(File(root, "blobs").canonicalPath + File.separator))
            assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.blob")))
        }
        assertTrue(root.walkTopDown().none { it.name.contains(PROFILE_ID) || it.name.contains("kernel.bin") })

        val second = repository.prepare(envelope)
        assertEquals(prepared.candidate, second.candidate)
        assertEquals(3, fetcher.calls.get())

        prepared.artifactFiles.getValue(ArtifactRole.KERNEL).writeBytes(ByteArray(artifacts.getValue(ArtifactRole.KERNEL).size))
        assertFailure<ProfileRepositoryCorruptException> { repository.prepare(envelope) }
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun `transport contract and max-plus-one reject redirect encoding lengths and bad streams`() {
        val good = artifactBytes("transport")
        val kernel = good.getValue(ArtifactRole.KERNEL)
        val cases = listOf<(ArtifactFetchRequest) -> ArtifactFetchResponse>(
            { request -> response(request, kernel, finalUrl = "$APPROVED_ORIGIN/other") },
            { request -> response(request, kernel, redirects = 1) },
            { request -> response(request, kernel, encoding = "gzip") },
            { request -> response(request, kernel, contentLength = kernel.size.toLong() + 1) },
            { request -> response(request, kernel.copyOf(kernel.size - 1), contentLength = kernel.size.toLong()) },
            { request -> response(request, kernel + byteArrayOf(1), contentLength = kernel.size.toLong()) },
            { request -> response(request, kernel.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) },
        )

        cases.forEachIndexed { index, badResponse ->
            val root = temporaryFolder.newFolder("bad-$index")
            val fetcher = ProfileArtifactFetcher { request -> badResponse(request) }
            val repository = repository(root, fetcher)

            assertFailure<ProfileDownloadException>("case $index") {
                repository.prepare(envelope(generation = 1, artifacts = good))
            }
            assertTrue(File(root, "blobs").listFiles().orEmpty().isEmpty())
            assertTrue(File(root, "prepared").walkTopDown().filter { it.isFile }.none())
            assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `streaming enforces the monotonic deadline between transport reads`() {
        val artifacts = artifactBytes("deadline")
        val kernel = artifacts.getValue(ArtifactRole.KERNEL)
        val fetcher = ProfileArtifactFetcher { request ->
            val delegate = ByteArrayInputStream(kernel)
            val slow = object : java.io.InputStream() {
                override fun read(): Int = delegate.read()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    Thread.sleep(5)
                    return delegate.read(buffer, offset, length)
                }
            }
            ArtifactFetchResponse(200, request.url.value, 0, null, kernel.size.toLong(), slow)
        }
        val repository = ProfileRepository(
            temporaryFolder.newFolder("deadline-root"),
            origins,
            { publicKey },
            fetcher,
            fetchTimeoutMillis = 1,
            lockTimeoutMillis = 5_000,
        )

        assertFailure<ProfileDownloadException> {
            repository.prepare(envelope(generation = 1, artifacts = artifacts))
        }
    }

    @Test
    fun `monotonic floors reject rollback and equal generation equivocation but allow exact retry`() {
        val first = artifactBytes("generation-two")
        val fetcher = RecordingFetcher(first)
        val repository = repository(temporaryFolder.newFolder("floors"), fetcher)
        val generationTwo = envelope(generation = 2, artifacts = first)
        val prepared = repository.prepare(generationTwo)

        repository.prepare(generationTwo)
        assertEquals(3, fetcher.calls.get())

        val lower = artifactBytes("generation-one")
        fetcher.replace(lower)
        assertFailure<ProfileGenerationRollbackException> {
            repository.prepare(envelope(generation = 1, artifacts = lower))
        }
        assertEquals(3, fetcher.calls.get())

        val equivocation = artifactBytes("generation-two-conflict")
        fetcher.replace(equivocation)
        assertFailure<ProfileGenerationEquivocationException> {
            repository.prepare(envelope(generation = 2, artifacts = equivocation))
        }
        assertEquals(3, fetcher.calls.get())
        assertEquals(ProfileGeneration(2), prepared.candidate.generation)
    }

    @Test
    fun `recovery removes bounded crash temps and reconstructs floor from immutable prepared record`() {
        val artifacts = artifactBytes("recover")
        val root = temporaryFolder.newFolder("recover-root")
        val repository = repository(root, RecordingFetcher(artifacts))
        val prepared = repository.prepare(envelope(generation = 4, artifacts = artifacts))
        val stateDirectory = File(root, "state")
        val floor = stateDirectory.listFiles().single { it.name.endsWith(".floor") }
        assertTrue(floor.delete())
        File(root, "tmp/artifact.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "tmp/record.tmp").writeBytes(byteArrayOf(4, 5, 6))

        repository.recover()

        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
        assertEquals(1, stateDirectory.listFiles().count { it.name.endsWith(".floor") })
        val storage = temporaryFolder.newFile("recover-storage.img")
        val activation = repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA, storage)
        assertEquals(1L, activation.activationSequence)
    }

    @Test
    fun `symlinks and corrupt immutable records fail closed`() {
        val artifacts = artifactBytes("hostile")
        val root = temporaryFolder.newFolder("hostile-root")
        val repository = repository(root, RecordingFetcher(artifacts))
        repository.recover()
        val link = File(root, "blobs/${"0".repeat(64)}.blob").toPath()
        Files.createSymbolicLink(link, temporaryFolder.newFile("outside").toPath())
        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }
        Files.delete(link)

        val prepared = repository.prepare(envelope(generation = 1, artifacts = artifacts))
        val record = File(root, "prepared").walkTopDown().single { it.isFile && it.name.endsWith(".prepared") }
        record.appendBytes(byteArrayOf(0))
        assertFailure<ProfileRepositoryCorruptException> {
            repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA, temporaryFolder.newFile("corrupt-storage"))
        }
    }

    @Test
    fun `preserve activation never changes storage and rollback is local sequence-bound and floor-preserving`() {
        val root = temporaryFolder.newFolder("activation-root")
        val generationOneBytes = artifactBytes("active-one")
        val fetcher = RecordingFetcher(generationOneBytes)
        val repository = repository(root, fetcher)
        val one = repository.prepare(envelope(generation = 1, artifacts = generationOneBytes))
        val storage = temporaryFolder.newFile("persistent-storage.img")
        val storageBytes = "persistent-user-data".toByteArray()
        storage.writeBytes(storageBytes)

        val first = repository.activate(one.candidate, GuestDataPolicy.PRESERVE_DATA, storage)
        assertEquals(1L, first.activationSequence)
        assertNull(first.rollback)
        assertArrayEquals(storageBytes, storage.readBytes())

        val generationTwoBytes = artifactBytes("active-two")
        fetcher.replace(generationTwoBytes)
        val two = repository.prepare(envelope(generation = 2, artifacts = generationTwoBytes))
        val second = repository.activate(two.candidate, GuestDataPolicy.PRESERVE_DATA, storage)
        assertEquals(2L, second.activationSequence)
        assertEquals(one.candidate, second.rollback)
        assertArrayEquals(storageBytes, storage.readBytes())

        val rolledBack = repository.rollback(2, GuestDataPolicy.PRESERVE_DATA, storage)
        assertEquals(3L, rolledBack.activationSequence)
        assertEquals(one.candidate, rolledBack.active)
        assertEquals(two.candidate, rolledBack.rollback)
        assertArrayEquals(storageBytes, storage.readBytes())
        assertFailure<ProfileActivationException> {
            repository.rollback(2, GuestDataPolicy.PRESERVE_DATA, storage)
        }
        assertFailure<ProfileActivationException> {
            repository.rollback(3, GuestDataPolicy.DELETE_DATA, storage)
        }
        assertFailure<ProfileGenerationRollbackException> {
            repository.prepare(envelope(generation = 1, artifacts = generationOneBytes))
        }
    }

    @Test
    fun `delete activation requires exact confirmation and duplicate response loss is idempotent`() {
        val root = temporaryFolder.newFolder("delete-root")
        val firstBytes = artifactBytes("delete-one")
        val fetcher = RecordingFetcher(firstBytes)
        val repository = repository(root, fetcher)
        val first = repository.prepare(envelope(generation = 1, artifacts = firstBytes))
        val storage = temporaryFolder.newFile("delete-storage.img")
        storage.writeText("must survive rejection")
        repository.activate(first.candidate, GuestDataPolicy.PRESERVE_DATA, storage)

        val secondBytes = artifactBytes("delete-two")
        fetcher.replace(secondBytes)
        val second = repository.prepare(
            envelope(generation = 2, compatibility = "incompatible-v2", artifacts = secondBytes),
        )
        assertFailure<ProfileActivationException> {
            repository.activate(second.candidate, GuestDataPolicy.PRESERVE_DATA, storage)
        }
        assertTrue(storage.exists())
        assertFailure<ProfileActivationException> {
            repository.activate(
                second.candidate,
                GuestDataPolicy.DELETE_DATA,
                storage,
                DataDeletionConfirmation.confirm(0, second.candidate, storage),
            )
        }
        assertTrue(storage.exists())
        assertFailure<ProfileActivationException> {
            repository.activate(
                second.candidate,
                GuestDataPolicy.DELETE_DATA,
                storage,
                DataDeletionConfirmation.confirm(1, first.candidate, storage),
            )
        }
        assertTrue(storage.exists())

        val otherStorage = temporaryFolder.newFile("other-delete-storage.img")
        assertFailure<ProfileActivationException> {
            repository.activate(
                second.candidate,
                GuestDataPolicy.DELETE_DATA,
                storage,
                DataDeletionConfirmation.confirm(1, second.candidate, otherStorage),
            )
        }
        assertTrue(storage.exists())

        val confirmation = DataDeletionConfirmation.confirm(1, second.candidate, storage)
        val activated = repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA, storage, confirmation)
        assertEquals(2L, activated.activationSequence)
        assertFalse(storage.exists())

        storage.writeText("new data after response loss")
        val duplicate = repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA, storage, confirmation)
        assertEquals(activated, duplicate)
        assertTrue(storage.exists())
        assertFailure<ProfileActivationException> {
            repository.rollback(2, GuestDataPolicy.PRESERVE_DATA, storage)
        }
        assertTrue(storage.exists())
    }

    @Test
    fun `confirmed deletion intent recovers crashes before and after storage deletion`() {
        ProfileRepositoryFaultPoint.entries.forEach { faultPoint ->
            val root = temporaryFolder.newFolder("delete-crash-${faultPoint.ordinal}")
            val firstBytes = artifactBytes("crash-one-${faultPoint.ordinal}")
            val fetcher = RecordingFetcher(firstBytes)
            val armed = AtomicInteger(1)
            val crashing = repository(
                root,
                fetcher,
                ProfileRepositoryFaultInjector { point ->
                    if (point == faultPoint && armed.getAndDecrement() > 0) throw IOException("simulated process death")
                },
            )
            val first = crashing.prepare(envelope(generation = 1, artifacts = firstBytes))
            val storage = File(root.parentFile, "crash-storage-${faultPoint.ordinal}.img").also { it.writeText("data") }
            crashing.activate(first.candidate, GuestDataPolicy.PRESERVE_DATA, storage)
            val secondBytes = artifactBytes("crash-two-${faultPoint.ordinal}")
            fetcher.replace(secondBytes)
            val second = crashing.prepare(envelope(generation = 2, artifacts = secondBytes))
            val confirmation = DataDeletionConfirmation.confirm(1, second.candidate, storage)

            assertFailure<IOException> {
                crashing.activate(second.candidate, GuestDataPolicy.DELETE_DATA, storage, confirmation)
            }
            assertEquals(faultPoint == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT, storage.exists())
            assertFailure<ProfileActivationException> { crashing.activationState() }

            val restarted = repository(root, fetcher)
            val recovered = restarted.activate(second.candidate, GuestDataPolicy.DELETE_DATA, storage)
            assertEquals(2L, recovered.activationSequence)
            assertEquals(second.candidate, recovered.active)
            assertFalse(storage.exists())
            assertEquals(recovered, restarted.activationState())
        }
    }

    @Test
    fun `concurrent prepares serialize all injected fetches across repository instances`() {
        val firstBytes = artifactBytes("concurrent-a")
        val secondBytes = artifactBytes("concurrent-b")
        val allBytes = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
        val activeFetches = AtomicInteger()
        val maximumFetches = AtomicInteger()
        val fetchCalls = AtomicInteger()
        val fetcher = ProfileArtifactFetcher { request ->
            fetchCalls.incrementAndGet()
            val active = activeFetches.incrementAndGet()
            maximumFetches.accumulateAndGet(active, ::maxOf)
            try {
                Thread.sleep(10)
                val bytes = allBytes.getValue(request.url.value)
                response(request, bytes)
            } finally {
                activeFetches.decrementAndGet()
            }
        }
        addArtifactUrls(allBytes, "profile-a", firstBytes)
        addArtifactUrls(allBytes, "profile-b", secondBytes)
        val concurrentRoot = temporaryFolder.newFolder("concurrent-root")
        val firstRepository = repository(concurrentRoot, fetcher)
        val secondRepository = repository(concurrentRoot, fetcher)
        val firstEnvelope = envelope(1, "profile-a", COMPATIBILITY, firstBytes)
        val secondEnvelope = envelope(1, "profile-b", COMPATIBILITY, secondBytes)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit<PreparedProfile> { firstRepository.prepare(firstEnvelope) },
                executor.submit<PreparedProfile> { secondRepository.prepare(secondEnvelope) },
            )
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(6, fetchCalls.get())
        assertEquals(1, maximumFetches.get())
    }

    private fun repository(
        root: File,
        fetcher: ProfileArtifactFetcher,
        faultInjector: ProfileRepositoryFaultInjector = ProfileRepositoryFaultInjector { },
    ): ProfileRepository = ProfileRepository(
        root,
        origins,
        resolvePublicKey = { publicKey },
        artifactFetcher = fetcher,
        fetchTimeoutMillis = 5_000,
        lockTimeoutMillis = 5_000,
        faultInjector = faultInjector,
    )

    private fun envelope(
        generation: Long,
        profileId: String = PROFILE_ID,
        compatibility: String = COMPATIBILITY,
        artifacts: Map<ArtifactRole, ByteArray>,
    ): ByteArray {
        val profileArtifacts = ArtifactRole.entries.map { role ->
            val bytes = artifacts.getValue(role)
            ProfileArtifact(
                role,
                origins.parseUrl(url(profileId, role)),
                Sha256Digest(bytes.sha256()),
                ArtifactSizeBytes(bytes.size.toLong()),
            )
        }
        val payload = ProfilePayloadJsonCodec.encode(
            VmProfile(
                ProfileId(profileId),
                ProfileGeneration(generation),
                DataCompatibilityId(compatibility),
                profileArtifacts,
            ),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(ProfileSigning.messageFor(payload))
            sign()
        }
        return SignedProfileEnvelopeJsonCodec.encode(SigningKeyId("release-1"), payload, signature)
    }

    private fun artifactBytes(seed: String): Map<ArtifactRole, ByteArray> = ArtifactRole.entries.associateWith { role ->
        "$seed-${role.wireName}-verified-content".repeat(4).toByteArray()
    }

    private fun addArtifactUrls(
        destination: MutableMap<String, ByteArray>,
        profileId: String,
        artifacts: Map<ArtifactRole, ByteArray>,
    ) {
        artifacts.forEach { (role, bytes) -> destination[url(profileId, role)] = bytes }
    }

    private fun url(profileId: String, role: ArtifactRole): String =
        "$APPROVED_ORIGIN/$profileId/${role.wireName}.bin"

    private fun response(
        request: ArtifactFetchRequest,
        bytes: ByteArray,
        finalUrl: String = request.url.value,
        redirects: Int = 0,
        encoding: String? = null,
        contentLength: Long? = bytes.size.toLong(),
    ): ArtifactFetchResponse = ArtifactFetchResponse(
        statusCode = 200,
        finalUrl = finalUrl,
        redirectCount = redirects,
        contentEncoding = encoding,
        contentLengthBytes = contentLength,
        body = ByteArrayInputStream(bytes),
    )

    private inline fun <reified T : Throwable> assertFailure(label: String = "", block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("$label expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private inner class RecordingFetcher(initial: Map<ArtifactRole, ByteArray>) : ProfileArtifactFetcher {
        val calls = AtomicInteger()
        private var bytesByUrl = mutableMapOf<String, ByteArray>()

        init {
            replace(initial)
        }

        @Synchronized
        fun replace(artifacts: Map<ArtifactRole, ByteArray>, profileId: String = PROFILE_ID) {
            bytesByUrl = ArtifactRole.entries.associate { role -> url(profileId, role) to artifacts.getValue(role) }.toMutableMap()
        }

        override fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse {
            calls.incrementAndGet()
            val bytes = synchronized(this) { bytesByUrl[request.url.value] }
                ?: throw IOException("unexpected URL ${request.url.value}")
            assertEquals(bytes.size.toLong() + 1L, request.maxResponseBytes)
            assertTrue(request.deadlineNanos - System.nanoTime() > 0L)
            return response(request, bytes)
        }
    }

    private companion object {
        const val APPROVED_ORIGIN = "https://profiles.example:443"
        const val PROFILE_ID = "alpine-direct"
        const val COMPATIBILITY = "alpine-direct-v1"
    }
}
