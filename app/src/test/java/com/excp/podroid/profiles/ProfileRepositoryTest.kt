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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val origins = ApprovedArtifactOrigins.of(APPROVED_ORIGIN)
    private val firstKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val secondKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val trust = MutableTrustResolver(TrustEpoch(1)).apply { trust(KEY_ID, firstKey) }

    @Test
    fun `prepare streams fixed CAS paths deduplicates and fully revalidates blobs`() {
        val artifacts = artifactBytes("first")
        val fetcher = RecordingFetcher(artifacts)
        val root = temporaryFolder.newFolder("repository")
        val repository = repository(root, fetcher)

        val prepared = repository.prepare(envelope(1, artifacts = artifacts))

        assertEquals(3, fetcher.calls.get())
        assertEquals(ProfileGeneration(1), prepared.candidate.generation)
        assertEquals(KEY_ID, prepared.candidate.signingKeyId)
        assertEquals(TrustEpoch(1), prepared.candidate.trustEpoch)
        prepared.artifactFiles.forEach { (role, file) ->
            assertArrayEquals(artifacts.getValue(role), file.readBytes())
            assertTrue(file.canonicalPath.startsWith(File(root, "blobs").canonicalPath + File.separator))
            assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.blob")))
        }

        assertEquals(prepared.candidate, repository.prepare(envelope(1, artifacts = artifacts)).candidate)
        assertEquals(3, fetcher.calls.get())
        prepared.artifactFiles.getValue(ArtifactRole.KERNEL).writeBytes(ByteArray(artifacts.getValue(ArtifactRole.KERNEL).size))
        assertFailure<ProfileRepositoryCorruptException> { repository.prepare(envelope(1, artifacts = artifacts)) }
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun `transport contract rejects redirects encoding lengths and bad streams and cleans new blobs`() {
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
            var call = 0
            val fetcher = ProfileArtifactFetcher { request ->
                if (call++ == 0) response(request, good.getValue(ArtifactRole.KERNEL)) else badResponse(request)
            }
            assertFailure<ProfileDownloadException>("case $index") {
                repository(root, fetcher).prepare(envelope(1, artifacts = good))
            }
            assertTrue(File(root, "blobs").listFiles().orEmpty().isEmpty())
            assertTrue(File(root, "prepared").walkTopDown().filter { it.isFile }.none())
            assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `same trust epoch is monotonic and higher trust epoch supersedes a poisoned high floor`() {
        val high = artifactBytes("epoch-one-high")
        val fetcher = RecordingFetcher(high)
        val repository = repository(temporaryFolder.newFolder("epochs"), fetcher)
        repository.prepare(envelope(ProfileLimits.MAX_PROFILE_GENERATION, artifacts = high))

        assertFailure<ProfileGenerationRollbackException> {
            repository.prepare(envelope(1, artifacts = high))
        }

        trust.epoch = TrustEpoch(2)
        trust.revoke(KEY_ID)
        trust.trust(SECOND_KEY_ID, secondKey)
        val low = artifactBytes("epoch-two-low")
        fetcher.replace(low)
        val reset = repository.prepare(envelope(1, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = low))
        assertEquals(ProfileGeneration(1), reset.candidate.generation)
        assertEquals(TrustEpoch(2), reset.candidate.trustEpoch)
    }

    @Test
    fun `equal generation equivocation is rejected before downloads`() {
        val first = artifactBytes("generation")
        val fetcher = RecordingFetcher(first)
        val repository = repository(temporaryFolder.newFolder("equivocation"), fetcher)
        repository.prepare(envelope(2, artifacts = first))
        val conflict = artifactBytes("conflict")
        fetcher.replace(conflict)

        assertFailure<ProfileGenerationEquivocationException> {
            repository.prepare(envelope(2, artifacts = conflict))
        }
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun `revoked prepared candidate cannot activate and current-active idempotency revalidates policy`() {
        val artifacts = artifactBytes("revoked")
        val root = temporaryFolder.newFolder("revoked")
        val repository = repository(root, RecordingFetcher(artifacts))
        val prepared = repository.prepare(envelope(1, artifacts = artifacts))
        repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)

        trust.revoke(KEY_ID)
        assertFailure<ProfileActivationException> {
            repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)
        }
        assertFailure<ProfileActivationException> {
            repository.issueDataDeletionConfirmation(prepared.candidate)
        }
    }

    @Test
    fun `preserve activation and rollback are compatibility sequence and trust gated`() {
        val root = temporaryFolder.newFolder("activation")
        val storage = storageFor(root).also { it.writeText("persistent-user-data") }
        val firstBytes = artifactBytes("one")
        val fetcher = RecordingFetcher(firstBytes)
        val repository = repository(root, fetcher, storage = storage)
        val one = repository.prepare(envelope(1, artifacts = firstBytes))
        val first = repository.activate(one.candidate, GuestDataPolicy.PRESERVE_DATA)
        assertEquals(1L, first.activationSequence)
        assertNull(first.rollback)
        assertEquals("persistent-user-data", storage.readText())

        val secondBytes = artifactBytes("two")
        fetcher.replace(secondBytes)
        val two = repository.prepare(envelope(2, artifacts = secondBytes))
        val second = repository.activate(two.candidate, GuestDataPolicy.PRESERVE_DATA)
        assertEquals(one.candidate, second.rollback)
        val rolledBack = repository.rollback(2, GuestDataPolicy.PRESERVE_DATA)
        assertEquals(one.candidate, rolledBack.active)
        assertEquals("persistent-user-data", storage.readText())
        assertFailure<ProfileActivationException> { repository.rollback(2, GuestDataPolicy.PRESERVE_DATA) }
        assertFailure<ProfileActivationException> { repository.rollback(3, GuestDataPolicy.DELETE_DATA) }

        trust.revoke(KEY_ID)
        assertFailure<ProfileActivationException> { repository.rollback(3, GuestDataPolicy.PRESERVE_DATA) }
    }

    @Test
    fun `incompatible preserve is rejected while repository-issued delete token is exact and idempotent`() {
        val root = temporaryFolder.newFolder("delete")
        val storage = storageFor(root).also { it.writeText("must survive rejection") }
        val firstBytes = artifactBytes("delete-one")
        val fetcher = RecordingFetcher(firstBytes)
        val repository = repository(root, fetcher, storage = storage)
        val first = repository.prepare(envelope(1, artifacts = firstBytes))
        repository.activate(first.candidate, GuestDataPolicy.PRESERVE_DATA)
        val secondBytes = artifactBytes("delete-two")
        fetcher.replace(secondBytes)
        val second = repository.prepare(envelope(2, compatibility = "incompatible-v2", artifacts = secondBytes))

        assertFailure<ProfileActivationException> {
            repository.activate(second.candidate, GuestDataPolicy.PRESERVE_DATA)
        }
        assertFailure<ProfileActivationException> {
            repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA)
        }
        assertTrue(storage.exists())

        val confirmation = repository.issueDataDeletionConfirmation(second.candidate)
        val activated = repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        assertFalse(storage.exists())
        storage.writeText("new data after response loss")
        assertEquals(activated, repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA))
        assertTrue(storage.exists())
    }

    @Test
    fun `token from another repository cannot authorize deletion`() {
        val firstRoot = temporaryFolder.newFolder("token-first")
        val secondRoot = temporaryFolder.newFolder("token-second")
        val artifacts = artifactBytes("token")
        val first = repository(firstRoot, RecordingFetcher(artifacts))
        val second = repository(secondRoot, RecordingFetcher(artifacts))
        val firstCandidate = first.prepare(envelope(1, artifacts = artifacts)).candidate
        val secondCandidate = second.prepare(envelope(1, artifacts = artifacts)).candidate
        val foreign = first.issueDataDeletionConfirmation(firstCandidate)

        assertFailure<ProfileActivationException> {
            second.activate(secondCandidate, GuestDataPolicy.DELETE_DATA, foreign)
        }
        assertTrue(storageFor(secondRoot).exists())
    }

    @Test
    fun `storage replacement after confirmation fails closed without deletion`() {
        val root = temporaryFolder.newFolder("replacement")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("replacement")
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val confirmation = repository.issueDataDeletionConfirmation(candidate)
        assertTrue(storage.delete())
        storage.writeText("replacement")

        assertFailure<ProfileActivationException> {
            repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        }
        assertEquals("replacement", storage.readText())
    }

    @Test
    fun `replacement racing durable intent is quarantined checked and restored without deletion`() {
        val root = temporaryFolder.newFolder("replacement-race")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("replacement-race")
        var replaceOnIntentSync = false
        val racingDurability = DirectoryDurability { directory ->
            FileChannelDirectoryDurability.force(directory)
            if (replaceOnIntentSync && directory == File(root, "state").toPath() &&
                File(root, "state/activation.pending").exists()
            ) {
                replaceOnIntentSync = false
                assertTrue(storage.delete())
                storage.writeText("racing-replacement")
            }
        }
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage, durability = racingDurability)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val confirmation = repository.issueDataDeletionConfirmation(candidate)
        replaceOnIntentSync = true

        assertFailure<ProfileActivationException> {
            repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        }
        assertEquals("racing-replacement", storage.readText())
    }

    @Test
    fun `pending recovery revalidates replacement trust candidate floor and blobs before deletion`() {
        listOf("replacement", "revocation", "blob").forEach { scenario ->
            val root = temporaryFolder.newFolder("pending-$scenario")
            val storage = storageFor(root).also { it.writeText("original") }
            val artifacts = artifactBytes("pending-$scenario")
            val armed = AtomicInteger(1)
            val repository = repository(
                root,
                RecordingFetcher(artifacts),
                storage = storage,
                faultInjector = ProfileRepositoryFaultInjector { point ->
                    if (point == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT && armed.getAndDecrement() > 0) {
                        throw IOException("crash after intent")
                    }
                },
            )
            val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
            val confirmation = repository.issueDataDeletionConfirmation(candidate)
            assertFailure<IOException> {
                repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
            }
            when (scenario) {
                "replacement" -> { assertTrue(storage.delete()); storage.writeText("replacement") }
                "revocation" -> trust.revoke(KEY_ID)
                "blob" -> File(root, "blobs").listFiles()!!.first().writeText("corrupt")
            }

            assertFailure<ProfileRepositoryException> { repository.recover() }
            assertTrue(storage.exists())
            if (scenario == "replacement") assertEquals("replacement", storage.readText())
            trust.trust(KEY_ID, firstKey)
        }
    }

    @Test
    fun `prepare is blocked while deletion is pending so floor cannot outrun candidate`() {
        val root = temporaryFolder.newFolder("pending-block")
        val storage = storageFor(root).also { it.writeText("data") }
        val firstBytes = artifactBytes("pending-one")
        val fetcher = RecordingFetcher(firstBytes)
        val repository = repository(
            root,
            fetcher,
            storage = storage,
            faultInjector = ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT) throw IOException("crash")
            },
        )
        val first = repository.prepare(envelope(1, artifacts = firstBytes))
        assertFailure<IOException> {
            repository.activate(
                first.candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(first.candidate),
            )
        }
        val calls = fetcher.calls.get()
        val newer = artifactBytes("pending-two")
        fetcher.replace(newer)
        assertFailure<ProfileActivationException> { repository.prepare(envelope(2, artifacts = newer)) }
        assertEquals(calls, fetcher.calls.get())
        assertTrue(storage.exists())
    }

    @Test
    fun `pending intent directory sync failure aborts before destructive effect`() {
        val root = temporaryFolder.newFolder("durability")
        val storage = storageFor(root).also { it.writeText("data") }
        val artifacts = artifactBytes("durability")
        val failing = DirectoryDurability { directory ->
            if (directory == File(root, "state").toPath() && File(root, "state/activation.pending").exists()) {
                throw IOException("injected state directory fsync failure")
            }
            FileChannelDirectoryDurability.force(directory)
        }
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage, durability = failing)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val confirmation = repository.issueDataDeletionConfirmation(candidate)

        assertFailure<ProfileRepositoryException> {
            repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        }
        assertTrue(storage.exists())
        assertEquals("data", storage.readText())
    }

    @Test
    fun `durable deletion intent recovers crashes before and after deletion`() {
        ProfileRepositoryFaultPoint.entries.forEach { faultPoint ->
            val root = temporaryFolder.newFolder("crash-${faultPoint.ordinal}")
            val storage = storageFor(root).also { it.writeText("data") }
            val artifacts = artifactBytes("crash-${faultPoint.ordinal}")
            val armed = AtomicInteger(1)
            val crashing = repository(
                root,
                RecordingFetcher(artifacts),
                storage = storage,
                faultInjector = ProfileRepositoryFaultInjector { point ->
                    if (point == faultPoint && armed.getAndDecrement() > 0) throw IOException("process death")
                },
            )
            val candidate = crashing.prepare(envelope(1, artifacts = artifacts)).candidate
            assertFailure<IOException> {
                crashing.activate(
                    candidate,
                    GuestDataPolicy.DELETE_DATA,
                    crashing.issueDataDeletionConfirmation(candidate),
                )
            }
            assertEquals(faultPoint == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT, storage.exists())

            val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)
            restarted.recover()
            assertEquals(candidate, restarted.activationState()!!.active)
            assertFalse(storage.exists())
        }
    }

    @Test
    fun `CAS aggregate byte and count quotas reject before download`() {
        val bytes = artifactBytes("quota")
        val total = bytes.values.sumOf { it.size.toLong() }
        val byteFetcher = RecordingFetcher(bytes)
        val byteRoot = temporaryFolder.newFolder("byte-quota")
        val byteRepository = repository(
            byteRoot,
            byteFetcher,
            limits = ProfileStoreLimits(total - 1, 3, 0),
        )
        assertFailure<ProfileQuotaExceededException> { byteRepository.prepare(envelope(1, artifacts = bytes)) }
        assertEquals(0, byteFetcher.calls.get())

        val countRoot = temporaryFolder.newFolder("count-quota")
        val countFetcher = RecordingFetcher(bytes)
        val countRepository = repository(
            countRoot,
            countFetcher,
            limits = ProfileStoreLimits(total * 10, 3, 0),
        )
        countRepository.prepare(envelope(1, artifacts = bytes))
        val newer = artifactBytes("quota-new")
        countFetcher.replace(newer)
        assertFailure<ProfileQuotaExceededException> { countRepository.prepare(envelope(2, artifacts = newer)) }
        assertEquals(3, countFetcher.calls.get())
    }

    @Test
    fun `bounded GC deletes only orphan blobs and preserves every prepared reference`() {
        val root = temporaryFolder.newFolder("gc")
        val artifacts = artifactBytes("gc")
        val repository = repository(root, RecordingFetcher(artifacts))
        val prepared = repository.prepare(envelope(1, artifacts = artifacts))
        val orphanBytes = "orphan".toByteArray()
        val orphan = File(root, "blobs/${orphanBytes.sha256()}.blob").also { it.writeBytes(orphanBytes) }

        val result = repository.collectGarbage()
        assertEquals(1, result.deletedBlobCount)
        assertEquals(orphanBytes.size.toLong(), result.deletedBytes)
        assertFalse(orphan.exists())
        prepared.artifactFiles.values.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `symlink and corrupt immutable state fail closed`() {
        val root = temporaryFolder.newFolder("hostile")
        val artifacts = artifactBytes("hostile")
        val repository = repository(root, RecordingFetcher(artifacts))
        repository.recover()
        val link = File(root, "blobs/${"0".repeat(64)}.blob").toPath()
        Files.createSymbolicLink(link, temporaryFolder.newFile("outside").toPath())
        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }
        Files.delete(link)
        repository.prepare(envelope(1, artifacts = artifacts))
        File(root, "prepared").walkTopDown().single { it.isFile }.appendBytes(byteArrayOf(0))
        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }
    }

    @Test
    fun `prepared filenames and future trust epochs are bound and fail closed`() {
        val root = temporaryFolder.newFolder("record-binding")
        val artifacts = artifactBytes("record-binding")
        val repository = repository(root, RecordingFetcher(artifacts))
        repository.prepare(envelope(1, artifacts = artifacts))
        val record = File(root, "prepared").walkTopDown().single { it.isFile }
        val renamed = File(record.parentFile, "1-2.prepared")
        assertTrue(record.renameTo(renamed))
        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }
        assertTrue(renamed.renameTo(record))

        trust.epoch = TrustEpoch(2)
        trust.revoke(KEY_ID)
        trust.trust(SECOND_KEY_ID, secondKey)
        val newer = artifactBytes("future-epoch")
        repository(root, RecordingFetcher(newer)).prepare(
            envelope(1, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = newer),
        )
        trust.epoch = TrustEpoch(1)
        trust.trust(KEY_ID, firstKey)
        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }
    }

    @Test
    fun `concurrent prepares serialize one stream across repository instances`() {
        val firstBytes = artifactBytes("concurrent-a")
        val secondBytes = artifactBytes("concurrent-b")
        val allBytes = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
        addArtifactUrls(allBytes, "profile-a", firstBytes)
        addArtifactUrls(allBytes, "profile-b", secondBytes)
        val activeFetches = AtomicInteger()
        val maximumFetches = AtomicInteger()
        val fetcher = ProfileArtifactFetcher { request ->
            val active = activeFetches.incrementAndGet()
            maximumFetches.accumulateAndGet(active, ::maxOf)
            try {
                Thread.sleep(10)
                response(request, allBytes.getValue(request.url.value))
            } finally {
                activeFetches.decrementAndGet()
            }
        }
        val root = temporaryFolder.newFolder("concurrent")
        val storage = storageFor(root)
        val first = repository(root, fetcher, storage = storage)
        val second = repository(root, fetcher, storage = storage)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit<PreparedProfile> { first.prepare(envelope(1, "profile-a", artifacts = firstBytes)) },
                executor.submit<PreparedProfile> { second.prepare(envelope(1, "profile-b", artifacts = secondBytes)) },
            )
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, maximumFetches.get())
    }

    private fun repository(
        root: File,
        fetcher: ProfileArtifactFetcher,
        storage: File = storageFor(root),
        durability: DirectoryDurability = FileChannelDirectoryDurability,
        limits: ProfileStoreLimits = ProfileStoreLimits(reservedFreeBytes = 0),
        faultInjector: ProfileRepositoryFaultInjector = ProfileRepositoryFaultInjector { },
    ): ProfileRepository = ProfileRepository(
        repositoryDirectory = root,
        storageFile = storage,
        approvedOrigins = origins,
        trustResolver = trust,
        artifactFetcher = fetcher,
        verifier = JcaEd25519Verifier,
        directoryDurability = durability,
        storeLimits = limits,
        fetchTimeoutMillis = 5_000,
        lockTimeoutMillis = 5_000,
        faultInjector = faultInjector,
    )

    private fun storageFor(root: File): File = File(root.parentFile, "${root.name}.storage").also {
        if (!it.exists()) it.writeText("storage")
    }

    private fun envelope(
        generation: Long,
        profileId: String = PROFILE_ID,
        compatibility: String = COMPATIBILITY,
        keyId: SigningKeyId = KEY_ID,
        keyPair: KeyPair = firstKey,
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
            VmProfile(ProfileId(profileId), ProfileGeneration(generation), DataCompatibilityId(compatibility), profileArtifacts),
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(ProfileSigning.messageFor(payload))
            sign()
        }
        return SignedProfileEnvelopeJsonCodec.encode(keyId, payload, signature)
    }

    private fun artifactBytes(seed: String): Map<ArtifactRole, ByteArray> = ArtifactRole.entries.associateWith { role ->
        "$seed-${role.wireName}-verified-content".repeat(4).toByteArray()
    }

    private fun addArtifactUrls(destination: MutableMap<String, ByteArray>, profileId: String, artifacts: Map<ArtifactRole, ByteArray>) {
        artifacts.forEach { (role, bytes) -> destination[url(profileId, role)] = bytes }
    }

    private fun url(profileId: String, role: ArtifactRole): String = "$APPROVED_ORIGIN/$profileId/${role.wireName}.bin"

    private fun response(
        request: ArtifactFetchRequest,
        bytes: ByteArray,
        finalUrl: String = request.url.value,
        redirects: Int = 0,
        encoding: String? = null,
        contentLength: Long? = bytes.size.toLong(),
    ): ArtifactFetchResponse = ArtifactFetchResponse(
        200, finalUrl, redirects, encoding, contentLength, ByteArrayInputStream(bytes),
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

        init { replace(initial) }

        @Synchronized
        fun replace(artifacts: Map<ArtifactRole, ByteArray>, profileId: String = PROFILE_ID) {
            bytesByUrl = ArtifactRole.entries.associate { role -> url(profileId, role) to artifacts.getValue(role) }.toMutableMap()
        }

        override fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse {
            calls.incrementAndGet()
            val bytes = synchronized(this) { bytesByUrl[request.url.value] }
                ?: throw IOException("unexpected URL ${request.url.value}")
            assertEquals(bytes.size.toLong() + 1L, request.maxResponseBytes)
            return response(request, bytes)
        }
    }

    private class MutableTrustResolver(var epoch: TrustEpoch) : ProfileTrustResolver {
        private val keys = mutableMapOf<SigningKeyId, TrustedProfileSigningKey>()
        override val currentTrustEpoch: TrustEpoch get() = epoch
        fun trust(keyId: SigningKeyId, keyPair: KeyPair) {
            keys[keyId] = TrustedProfileSigningKey(Ed25519PublicKey.fromX509(keyPair.public.encoded))
        }
        fun revoke(keyId: SigningKeyId) { keys.remove(keyId) }
        override fun resolve(keyId: SigningKeyId): TrustedProfileSigningKey? = keys[keyId]
    }

    private companion object {
        const val APPROVED_ORIGIN = "https://profiles.example:443"
        const val PROFILE_ID = "alpine-direct"
        const val COMPATIBILITY = "alpine-direct-v1"
        val KEY_ID = SigningKeyId("release-1")
        val SECOND_KEY_ID = SigningKeyId("release-2")
    }
}
