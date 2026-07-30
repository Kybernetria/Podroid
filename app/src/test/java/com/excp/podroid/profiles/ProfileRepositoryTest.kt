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
    private var trustPolicy = policy(TrustEpoch(1), KEY_ID to firstKey)

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
        val root = temporaryFolder.newFolder("epochs")
        var repository = repository(root, fetcher)
        val poisoned = repository.prepare(envelope(ProfileLimits.MAX_PROFILE_GENERATION, artifacts = high))
        repository.activate(poisoned.candidate, GuestDataPolicy.PRESERVE_DATA)

        assertFailure<ProfileGenerationRollbackException> {
            repository.prepare(envelope(1, artifacts = high))
        }

        trustPolicy = policy(TrustEpoch(2), SECOND_KEY_ID to secondKey)
        repository = repository(root, fetcher)
        val low = artifactBytes("epoch-two-low")
        fetcher.replace(low)
        val reset = repository.prepare(envelope(1, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = low))
        assertEquals(ProfileGeneration(1), reset.candidate.generation)
        assertEquals(TrustEpoch(2), reset.candidate.trustEpoch)
        assertNull(repository.activationState())
        assertEquals(
            TrustQuarantineReason.TRUST_EPOCH_OBSOLETE,
            repository.lastTrustQuarantine()!!.candidates.single().reason,
        )
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
    fun `repository captures trust for its lifetime and restart quarantines a revoked active`() {
        val artifacts = artifactBytes("revoked")
        val root = temporaryFolder.newFolder("revoked")
        val storage = storageFor(root).also { it.writeText("preserved") }
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage)
        val prepared = repository.prepare(envelope(1, artifacts = artifacts))
        val active = repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)

        trustPolicy = policy(TrustEpoch(1))
        assertEquals(active, repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA))

        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)
        restarted.recover()

        assertNull(restarted.activationState())
        assertEquals("preserved", storage.readText())
        val quarantine = restarted.lastTrustQuarantine()!!
        assertEquals(active.activationSequence, quarantine.activationSequence)
        assertEquals(prepared.candidate, quarantine.candidates.single().candidate)
        assertEquals(TrustQuarantineReason.SIGNING_KEY_NOT_TRUSTED, quarantine.candidates.single().reason)
        assertFailure<ProfileActivationException> {
            restarted.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)
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

        trustPolicy = policy(TrustEpoch(1))
        val restarted = repository(root, fetcher, storage = storage)
        assertFailure<ProfileActivationException> { restarted.rollback(3, GuestDataPolicy.PRESERVE_DATA) }
        assertNull(restarted.activationState())
        assertEquals("persistent-user-data", storage.readText())
    }

    @Test
    fun `first preserve with bundled storage requires bundled Alpine lineage`() {
        val root = temporaryFolder.newFolder("first-preserve-lineage")
        val storage = storageFor(root).also { it.writeText("bundled-overlay-data") }
        val artifacts = artifactBytes("foreign-lineage")
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage)
        val candidate = repository.prepare(
            envelope(1, compatibility = "foreign-overlay-v1", artifacts = artifacts),
        ).candidate

        assertFailure<ProfileActivationException> {
            repository.activate(candidate, GuestDataPolicy.PRESERVE_DATA)
        }
        assertEquals("bundled-overlay-data", storage.readText())

        val confirmation = repository.issueDataDeletionConfirmation(candidate)
        repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        assertFalse(storage.exists())
    }

    @Test
    fun `incompatible preserve is rejected while repository-issued delete token is exact`() {
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
        assertFailure<ProfileActivationException> {
            repository.activate(second.candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        }
        assertTrue(storage.exists())
        assertEquals(activated, repository.activate(second.candidate, GuestDataPolicy.PRESERVE_DATA))
    }

    @Test
    fun `same-active delete is a new reset transaction and stale replay cannot delete recreated data`() {
        val root = temporaryFolder.newFolder("same-active-reset")
        val storage = storageFor(root).also { it.writeText("original data") }
        val artifacts = artifactBytes("same-active-reset")
        val repository = repository(root, RecordingFetcher(artifacts), storage = storage)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val first = repository.activate(candidate, GuestDataPolicy.PRESERVE_DATA)
        val confirmation = repository.issueDataDeletionConfirmation(candidate)

        val reset = repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)

        assertEquals(first.activationSequence + 1L, reset.activationSequence)
        assertEquals(candidate, reset.active)
        assertFalse(storage.exists())
        storage.writeText("recreated data")
        assertFailure<ProfileActivationException> {
            repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)
        }
        assertEquals("recreated data", storage.readText())
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
    fun `pending recovery rejects a replacement without deleting it`() {
        val root = temporaryFolder.newFolder("pending-replacement")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("pending-replacement")
        val repository = crashingRepositoryAfterIntent(root, storage, artifacts)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        assertTrue(storage.delete())
        storage.writeText("replacement")

        assertFailure<ProfileRepositoryException> { repository.recover() }
        assertEquals("replacement", storage.readText())
    }

    @Test
    fun `revoked pending activation restores a matching tombstone and records terminal failure`() {
        val root = temporaryFolder.newFolder("pending-revoked-tombstone")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("pending-revoked-tombstone")
        val repository = crashingRepositoryAfterIntent(root, storage, artifacts)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        val tombstone = storageTombstone(storage)
        Files.move(storage.toPath(), tombstone.toPath())
        trustPolicy = policy(TrustEpoch(1))
        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)

        restarted.recover()

        assertEquals("original", storage.readText())
        assertFalse(tombstone.exists())
        val failure = restarted.lastActivationFailure()!!
        assertEquals(candidate, failure.candidate)
        assertFalse(failure.storageDeletionIrreversible)
        assertFalse(File(root, "state/activation.pending").exists())
    }

    @Test
    fun `revoked pending activation restores original and quarantines recreated storage`() {
        val root = temporaryFolder.newFolder("pending-revoked-recreated")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("pending-revoked-recreated")
        val repository = crashingRepositoryAfterIntent(root, storage, artifacts)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        Files.move(storage.toPath(), storageTombstone(storage).toPath())
        storage.writeText("recreated")
        trustPolicy = policy(TrustEpoch(1))
        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)

        restarted.recover()

        assertEquals("original", storage.readText())
        assertEquals("recreated", storageQuarantine(storage).readText())
        assertFalse(storageTombstone(storage).exists())
        assertFalse(File(root, "state/activation.pending").exists())
    }

    @Test
    fun `revoked pending activation after irreversible deletion clears pending without activation`() {
        val root = temporaryFolder.newFolder("pending-revoked-deleted")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("pending-revoked-deleted")
        val armed = AtomicInteger(1)
        val repository = repository(
            root,
            RecordingFetcher(artifacts),
            storage = storage,
            faultInjector = ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION && armed.getAndDecrement() > 0) {
                    throw IOException("crash after deletion")
                }
            },
        )
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        trustPolicy = policy(TrustEpoch(1))
        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)

        restarted.recover()

        assertFalse(storage.exists())
        assertNull(restarted.activationState())
        assertTrue(restarted.lastActivationFailure()!!.storageDeletionIrreversible)
        assertFalse(File(root, "state/activation.pending").exists())
    }

    @Test
    fun `corrupt pending candidate is quarantined before deletion`() {
        val root = temporaryFolder.newFolder("pending-corrupt")
        val storage = storageFor(root).also { it.writeText("original") }
        val artifacts = artifactBytes("pending-corrupt")
        val repository = crashingRepositoryAfterIntent(root, storage, artifacts)
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        File(root, "blobs").listFiles()!!.first().writeText("corrupt")

        repository.recover()

        assertEquals("original", storage.readText())
        assertEquals(candidate, repository.lastActivationFailure()!!.candidate)
        assertFalse(File(root, "state/activation.pending").exists())
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
    fun `trust source mutation after durable intent cannot race destructive activation`() {
        val root = temporaryFolder.newFolder("intent-policy-snapshot")
        val storage = storageFor(root).also { it.writeText("data") }
        val artifacts = artifactBytes("intent-policy-snapshot")
        val mutableKeys = mutableMapOf(
            KEY_ID to TrustedProfileSigningKey(Ed25519PublicKey.fromX509(firstKey.public.encoded)),
        )
        trustPolicy = ProfileTrustPolicy(TrustEpoch(1), mutableKeys)
        var mutateOnIntentSync = true
        val durability = DirectoryDurability { directory ->
            FileChannelDirectoryDurability.force(directory)
            if (mutateOnIntentSync && directory == File(root, "state").toPath() &&
                File(root, "state/activation.pending").exists()
            ) {
                mutateOnIntentSync = false
                mutableKeys.clear()
            }
        }
        val repository = repository(
            root,
            RecordingFetcher(artifacts),
            storage = storage,
            durability = durability,
        )
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val confirmation = repository.issueDataDeletionConfirmation(candidate)

        val active = repository.activate(candidate, GuestDataPolicy.DELETE_DATA, confirmation)

        assertEquals(candidate, active.active)
        assertFalse(storage.exists())
        assertNull(repository.lastActivationFailure())
        assertFalse(File(root, "state/activation.pending").exists())
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
    fun `committed pending activation clears after revocation but active resolution rejects it`() {
        val root = temporaryFolder.newFolder("committed-pending-revoked")
        val storage = storageFor(root).also { it.writeText("data") }
        val artifacts = artifactBytes("committed-pending-revoked")
        var failActivationSync = true
        val durability = DirectoryDurability { directory ->
            FileChannelDirectoryDurability.force(directory)
            if (failActivationSync && directory == File(root, "state").toPath() &&
                File(root, "state/activation.pending").exists() && File(root, "state/activation.record").exists()
            ) {
                failActivationSync = false
                throw IOException("crash after activation publication")
            }
        }
        val repository = repository(
            root,
            RecordingFetcher(artifacts),
            storage = storage,
            durability = durability,
        )
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        assertFailure<ProfileRepositoryException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }
        trustPolicy = policy(TrustEpoch(1))

        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)
        restarted.recover()

        assertNull(restarted.activationState())
        assertFalse(File(root, "state/activation.pending").exists())
        assertFailure<ProfileActivationException> { restarted.resolveActiveProfile() }
        assertEquals(candidate, restarted.lastTrustQuarantine()!!.candidates.single().candidate)
    }

    @Test
    fun `same-active reset pending transaction recovers and advances its sequence`() {
        val root = temporaryFolder.newFolder("same-active-reset-recovery")
        val storage = storageFor(root).also { it.writeText("data") }
        val artifacts = artifactBytes("same-active-reset-recovery")
        val armed = AtomicInteger(1)
        val repository = repository(
            root,
            RecordingFetcher(artifacts),
            storage = storage,
            faultInjector = ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT && armed.getAndDecrement() > 0) {
                    throw IOException("crash after reset intent")
                }
            },
        )
        val candidate = repository.prepare(envelope(1, artifacts = artifacts)).candidate
        val active = repository.activate(candidate, GuestDataPolicy.PRESERVE_DATA)
        assertFailure<IOException> {
            repository.activate(
                candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(candidate),
            )
        }

        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)
        restarted.recover()

        assertEquals(active.activationSequence + 1L, restarted.activationState()!!.activationSequence)
        assertFalse(storage.exists())
    }

    @Test
    fun `durable deletion intent recovers crashes before and after deletion`() {
        listOf(
            ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT,
            ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION,
        ).forEach { faultPoint ->
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
    fun `active and candidate resolvers revalidate trust and blobs after restart`() {
        val root = temporaryFolder.newFolder("resolve")
        val storage = storageFor(root)
        val artifacts = artifactBytes("resolve")
        val candidate = repository(root, RecordingFetcher(artifacts), storage = storage)
            .prepare(envelope(1, artifacts = artifacts)).candidate
        repository(root, RecordingFetcher(artifacts), storage = storage)
            .activate(candidate, GuestDataPolicy.PRESERVE_DATA)

        val restarted = repository(root, RecordingFetcher(artifacts), storage = storage)
        assertEquals(candidate, restarted.resolveActiveProfile()!!.candidate)
        assertEquals(candidate, restarted.resolveCandidate(candidate).candidate)

        trustPolicy = policy(TrustEpoch(1))
        val revoked = repository(root, RecordingFetcher(artifacts), storage = storage)
        assertFailure<ProfileActivationException> { revoked.resolveActiveProfile() }
        assertFailure<ProfileActivationException> { revoked.resolveCandidate(candidate) }

        trustPolicy = policy(TrustEpoch(1), KEY_ID to firstKey)
        val trustedAgain = repository(root, RecordingFetcher(artifacts), storage = storage)
        File(root, "blobs").listFiles()!!.first().writeText("corrupt")
        assertFailure<ProfileRepositoryCorruptException> { trustedAgain.resolveCandidate(candidate) }
    }

    @Test
    fun `boot artifact source resolves the validated active generation after process restart`() {
        val root = temporaryFolder.newFolder("boot-source-restart")
        val storage = storageFor(root)
        val bytes = artifactBytes("boot-source")
        val firstRepository = repository(root, RecordingFetcher(bytes), storage = storage)
        val prepared = firstRepository.prepare(envelope(23, artifacts = bytes))
        firstRepository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)

        val restartedRepository = repository(root, RecordingFetcher(bytes), storage = storage)
        val resolved = RepositoryProfileBootArtifactSource(restartedRepository)
            .resolveActiveBootArtifacts("qemu") as com.excp.podroid.vm.VmBootArtifacts

        assertEquals(23L, resolved.generation.value)
        assertEquals(prepared.candidate.manifestSha256.value, resolved.manifestSha256.value)
        ArtifactRole.entries.forEach { role ->
            val selected = when (role) {
                ArtifactRole.KERNEL -> resolved.kernel
                ArtifactRole.INITRD -> resolved.initrd
                ArtifactRole.ROOTFS -> resolved.rootfs
            }
            assertEquals(prepared.artifactFiles.getValue(role), selected.file)
            assertEquals(prepared.artifactDigests.getValue(role).value, selected.sha256.value)
        }
        resolved.validateFiles()

        resolved.kernel.file.writeText("invalid-configured-active-profile")
        assertFailure<ProfileRepositoryCorruptException> {
            RepositoryProfileBootArtifactSource(restartedRepository).resolveActiveBootArtifacts("qemu")
        }
    }

    @Test
    fun `selected backend contract survives repository restart and blocks unsupported launch`() {
        val root = temporaryFolder.newFolder("backend-contract-restart")
        val storage = storageFor(root)
        val bytes = artifactBytes("qemu-only")
        val first = repository(root, RecordingFetcher(bytes), storage = storage)
        val candidate = first.prepare(
            envelope(1, supportedBackends = setOf(ProfileBackend.QEMU), artifacts = bytes),
        ).candidate
        first.activate(candidate, GuestDataPolicy.PRESERVE_DATA)

        val restarted = repository(root, RecordingFetcher(bytes), storage = storage)
        assertFailure<ProfileActivationException> {
            RepositoryProfileBootArtifactSource(restarted).resolveActiveBootArtifacts("avf")
        }
        assertEquals(
            candidate,
            restarted.resolveActiveProfile()!!.also {
                assertEquals(setOf(ProfileBackend.QEMU), it.supportedBackends)
            }.candidate,
        )
    }

    @Test
    fun `prepared generation pruning recovers the retention bound`() {
        val root = temporaryFolder.newFolder("generation-pruning")
        var artifacts = artifactBytes("generation-1")
        val fetcher = RecordingFetcher(artifacts)
        val repository = repository(root, fetcher)

        for (generation in 1L..70L) {
            artifacts = artifactBytes("generation-$generation")
            fetcher.replace(artifacts)
            repository.prepare(envelope(generation, artifacts = artifacts))
        }

        assertEquals(1, File(root, "prepared").walkTopDown().count { it.isFile })
        assertEquals(3, File(root, "blobs").listFiles()!!.size)
    }

    @Test
    fun `revoked epoch pruning recovers CAS generation and byte quota for a newer epoch`() {
        val root = temporaryFolder.newFolder("epoch-quota-recovery")
        val oldArtifacts = artifactBytes("epoch-quota-old")
        val totalBytes = oldArtifacts.values.sumOf { it.size.toLong() }
        val fetcher = RecordingFetcher(oldArtifacts)
        val repository = repository(
            root,
            fetcher,
            limits = ProfileStoreLimits(totalBytes, ArtifactRole.entries.size, 0),
        )
        repository.prepare(envelope(1, artifacts = oldArtifacts))

        trustPolicy = policy(TrustEpoch(2), SECOND_KEY_ID to secondKey)
        val restarted = repository(root, fetcher, limits = ProfileStoreLimits(totalBytes, ArtifactRole.entries.size, 0))
        val newArtifacts = artifactBytes("epoch-quota-new")
        fetcher.replace(newArtifacts)
        val prepared = restarted.prepare(
            envelope(1, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = newArtifacts),
        )

        assertEquals(TrustEpoch(2), prepared.candidate.trustEpoch)
        assertEquals(3, File(root, "blobs").listFiles()!!.size)
        prepared.artifactFiles.values.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `full active rollback quota is reclaimed after same epoch key revocation`() {
        val root = temporaryFolder.newFolder("active-rollback-trust-quota")
        val storage = storageFor(root).also { it.writeText("persistent-user-data") }
        val firstArtifacts = artifactBytes("old-one")
        val secondArtifacts = artifactBytes("old-two")
        val totalBytes = (firstArtifacts.values + secondArtifacts.values).sumOf { it.size.toLong() }
        val limits = ProfileStoreLimits(totalBytes, ArtifactRole.entries.size * 2, 0)
        val fetcher = RecordingFetcher(firstArtifacts)
        val original = repository(root, fetcher, storage = storage, limits = limits)
        val first = original.prepare(envelope(1, artifacts = firstArtifacts))
        original.activate(first.candidate, GuestDataPolicy.PRESERVE_DATA)
        fetcher.replace(secondArtifacts)
        val second = original.prepare(envelope(2, artifacts = secondArtifacts))
        original.activate(second.candidate, GuestDataPolicy.PRESERVE_DATA)
        assertEquals(ArtifactRole.entries.size * 2, File(root, "blobs").listFiles()!!.size)

        trustPolicy = policy(TrustEpoch(1), SECOND_KEY_ID to secondKey)
        val restarted = repository(root, fetcher, storage = storage, limits = limits)
        restarted.recover()

        assertFailure<ProfileActivationException> { restarted.resolveActiveProfile() }
        assertEquals("persistent-user-data", storage.readText())
        val quarantine = restarted.lastTrustQuarantine()!!
        assertEquals(setOf(first.candidate, second.candidate), quarantine.candidates.map { it.candidate }.toSet())
        assertTrue(quarantine.candidates.all { it.reason == TrustQuarantineReason.SIGNING_KEY_NOT_TRUSTED })
        assertEquals(ArtifactRole.entries.size, File(root, "blobs").listFiles()!!.size)

        val replacementArtifacts = artifactBytes("new-key")
        fetcher.replace(replacementArtifacts)
        val replacement = restarted.prepare(
            envelope(3, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = replacementArtifacts),
        )

        assertEquals(SECOND_KEY_ID, replacement.candidate.signingKeyId)
        assertFailure<ProfileActivationException> { restarted.resolveActiveProfile() }
        assertEquals(ArtifactRole.entries.size, File(root, "blobs").listFiles()!!.size)
        replacement.artifactFiles.values.forEach { assertTrue(it.exists()) }
        assertEquals("persistent-user-data", storage.readText())
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
    fun `floor missing its immutable prepared state fails before blob collection`() {
        val root = temporaryFolder.newFolder("orphan-floor")
        val artifacts = artifactBytes("orphan-floor")
        val repository = repository(root, RecordingFetcher(artifacts))
        repository.prepare(envelope(1, artifacts = artifacts))
        val blobs = File(root, "blobs").listFiles()!!.toSet()
        assertTrue(File(root, "prepared").walkTopDown().single { it.isFile }.delete())

        assertFailure<ProfileRepositoryCorruptException> { repository.recover() }

        assertEquals(blobs, File(root, "blobs").listFiles()!!.toSet())
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

        trustPolicy = policy(TrustEpoch(2), SECOND_KEY_ID to secondKey)
        val newer = artifactBytes("future-epoch")
        repository(root, RecordingFetcher(newer)).prepare(
            envelope(1, keyId = SECOND_KEY_ID, keyPair = secondKey, artifacts = newer),
        )
        trustPolicy = policy(TrustEpoch(1), KEY_ID to firstKey)
        assertFailure<ProfileRepositoryCorruptException> {
            repository(root, RecordingFetcher(artifacts)).recover()
        }
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

    private fun crashingRepositoryAfterIntent(
        root: File,
        storage: File,
        artifacts: Map<ArtifactRole, ByteArray>,
    ): ProfileRepository {
        val armed = AtomicInteger(1)
        return repository(
            root,
            RecordingFetcher(artifacts),
            storage = storage,
            faultInjector = ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT && armed.getAndDecrement() > 0) {
                    throw IOException("crash after intent")
                }
            },
        )
    }

    private fun storageTombstone(storage: File): File =
        File(storage.parentFile, ".podroid-profile-delete-${storagePathDigest(storage)}")

    private fun storageQuarantine(storage: File): File =
        File(storage.parentFile, ".podroid-profile-preserved-${storagePathDigest(storage)}")

    private fun storagePathDigest(storage: File): String = MessageDigest.getInstance("SHA-256")
        .digest(storage.toPath().toAbsolutePath().normalize().toString().toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

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
        trustPolicy = trustPolicy,
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
        supportedBackends: Set<ProfileBackend> = ProfileBackend.entries.toSet(),
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
            VmProfile(
                id = ProfileId(profileId),
                generation = ProfileGeneration(generation),
                dataCompatibility = DataCompatibilityId(compatibility),
                architecture = ProfileArchitecture.AARCH64,
                bootContract = ProfileBootContract.PODROID_DIRECT_V1,
                storageContract = ProfileStorageContract.PODROID_OVERLAY_EXT4_V1,
                healthContract = ProfileHealthContract.PODROID_READY_V1,
                supportedBackends = supportedBackends,
                artifacts = profileArtifacts,
            ),
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

    private fun policy(
        epoch: TrustEpoch,
        vararg trustedKeys: Pair<SigningKeyId, KeyPair>,
    ): ProfileTrustPolicy = ProfileTrustPolicy(
        epoch,
        trustedKeys.associate { (keyId, keyPair) ->
            keyId to TrustedProfileSigningKey(Ed25519PublicKey.fromX509(keyPair.public.encoded))
        },
    )

    private companion object {
        const val APPROVED_ORIGIN = "https://profiles.example:443"
        const val PROFILE_ID = "alpine-direct"
        const val COMPATIBILITY = "podroid-alpine-overlay-v1"
        val KEY_ID = SigningKeyId("release-1")
        val SECOND_KEY_ID = SigningKeyId("release-2")
    }
}
