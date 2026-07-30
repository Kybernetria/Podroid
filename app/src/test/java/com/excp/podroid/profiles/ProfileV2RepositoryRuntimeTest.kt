package com.excp.podroid.profiles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileV2RepositoryRuntimeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = SigningKeyId("release-v2")
    private val origins = ApprovedArtifactOrigins.of(ORIGIN)
    private val trust = ProfileTrustPolicy(
        TrustEpoch(3),
        mapOf(keyId to TrustedProfileSigningKey(Ed25519PublicKey.fromX509(keyPair.public.encoded))),
    )

    @Test
    fun `v2 preparation is typed deduplicated and explicit envelope domain cannot be confused`() {
        val fixture = fixture("prepare")
        val root = temporaryFolder.newFolder("prepare-root")
        val storage = File(root, "instance/storage.img")
        storage.parentFile.mkdirs()
        val fetcher = MapFetcher(fixture.bytes)
        val repository = repository(root, storage, fetcher)

        val prepared = repository.prepare(envelope(fixture.profile))

        assertTrue(prepared.plan is PreparedProfilePlan.UefiNoCloudV1)
        val plan = prepared.plan as PreparedProfilePlan.UefiNoCloudV1
        assertEquals(ProfileV2ArtifactRole.entries.toSet(), plan.artifactFiles.keys)
        assertEquals(setOf(ProfileBackend.QEMU), plan.supportedBackends)
        assertTrue(plan.capabilities.guestIntegrations.isEmpty())
        assertEquals(4, fetcher.calls)
        repository.prepare(envelope(fixture.profile))
        assertEquals(4, fetcher.calls)

        val payload = ProfilePayloadV2JsonCodec.encode(fixture.profile)
        val signature = sign(ProfileSigningV2.messageFor(payload))
        val ambiguousV1Envelope = SignedProfileEnvelopeJsonCodec.encode(keyId, payload, signature)
        assertFailure<InvalidProfileSignatureException> { repository.prepare(ambiguousV1Envelope) }
        val wrongDomain = String(envelope(fixture.profile)).replace(
            ProfileSigningV2.DOMAIN_ID,
            "com.excp.podroid.vm-profile.v1",
        ).toByteArray()
        assertFailure<ProfileCodecException> { repository.prepare(wrongDomain) }
    }

    @Test
    fun `runtime canonical NoCloud policy rejects arbitrary signed seed bytes`() {
        val fixture = fixture("arbitrary-seed")
        val root = temporaryFolder.newFolder("arbitrary-seed-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs() }
        val repository = repository(
            root, storage, MapFetcher(fixture.bytes),
            noCloudSeedPolicy = CanonicalNoCloudSeedPolicy,
        )

        assertFailure<ProfileDownloadException> { repository.prepare(envelope(fixture.profile)) }
        assertTrue(File(root, "store/prepared").walkTopDown().none { it.isFile })
    }

    @Test
    fun `cloud activation requires delete and atomically initializes fixed disk and vars without resize`() {
        val fixture = fixture("activate")
        val root = temporaryFolder.newFolder("activate-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("bundled") }
        val vars = File(storage.parentFile, "uefi-vars.fd")
        val repository = repository(root, storage, MapFetcher(fixture.bytes), varsFile = vars)
        val prepared = repository.prepare(envelope(fixture.profile))

        assertFailure<ProfileActivationException> {
            repository.activate(prepared.candidate, GuestDataPolicy.PRESERVE_DATA)
        }
        assertEquals("bundled", storage.readText())
        assertFalse(vars.exists())

        val state = repository.activate(
            prepared.candidate,
            GuestDataPolicy.DELETE_DATA,
            repository.issueDataDeletionConfirmation(prepared.candidate),
        )

        assertEquals(prepared.candidate, state.active)
        assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.CLOUD_DISK), storage.readBytes())
        assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE), vars.readBytes())
        assertEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.CLOUD_DISK).size.toLong(), storage.length())
        val resolved = RepositoryProfileBootArtifactSource(repository).resolveActiveBootArtifacts("qemu")
        assertTrue(resolved is com.excp.podroid.vm.UefiNoCloudVmBootPlan)
        val cloud = resolved as com.excp.podroid.vm.UefiNoCloudVmBootPlan
        assertEquals(storage.absoluteFile, cloud.cloudRootDisk)
        assertEquals(vars.absoluteFile, cloud.uefiVars)
        assertEquals(ProfileV2Limits.READINESS_MARKER, cloud.readinessMarker)
        assertFalse(cloud.capabilities.terminal)
        assertFalse(cloud.capabilities.hostBridge)
        assertFailure<ProfileActivationException> {
            RepositoryProfileBootArtifactSource(repository).resolveActiveBootArtifacts("avf")
        }
    }

    @Test
    fun `cloud activation recovers each durable initialization phase and preserve requires exact lineage`() {
        val fixture = fixture("recovery")
        val root = temporaryFolder.newFolder("recovery-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("bundled") }
        val vars = File(storage.parentFile, "uefi-vars.fd")
        val armed = AtomicBoolean(true)
        val crashing = repository(
            root, storage, MapFetcher(fixture.bytes), vars,
            ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED && armed.compareAndSet(true, false)) {
                    throw IOException("crash after fixed cloud disk publication")
                }
            },
        )
        val prepared = crashing.prepare(envelope(fixture.profile))
        assertFailure<IOException> {
            crashing.activate(
                prepared.candidate,
                GuestDataPolicy.DELETE_DATA,
                crashing.issueDataDeletionConfirmation(prepared.candidate),
            )
        }

        val restarted = repository(root, storage, MapFetcher(fixture.bytes), varsFile = vars)
        restarted.recover()
        assertEquals(prepared.candidate, restarted.activationState()!!.active)
        assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.CLOUD_DISK), storage.readBytes())
        assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE), vars.readBytes())

        val compatible = fixture("recovery", generation = 2, compatibility = fixture.profile.dataCompatibility)
        val compatiblePrepared = restarted.prepare(envelope(compatible.profile))
        val compatibleActivation = restarted.activate(compatiblePrepared.candidate, GuestDataPolicy.PRESERVE_DATA)
        val rolledBack = restarted.rollback(compatibleActivation.activationSequence, GuestDataPolicy.PRESERVE_DATA)
        assertEquals(prepared.candidate, rolledBack.active)

        val changed = fixture("changed", generation = 2, compatibility = fixture.profile.dataCompatibility)
        val changedFetcher = MapFetcher(fixture.bytes + changed.bytes)
        val changedRepository = repository(root, storage, changedFetcher, varsFile = vars)
        val changedPrepared = changedRepository.prepare(envelope(changed.profile))
        assertFailure<ProfileActivationException> {
            changedRepository.activate(changedPrepared.candidate, GuestDataPolicy.PRESERVE_DATA)
        }
    }

    @Test
    fun `every cloud phase crash has trusted recovery to one coherent generation`() {
        val faultPoints = listOf(
            ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_VARS_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_LINEAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ACTIVATION_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED,
        )
        faultPoints.forEach { faultPoint ->
            val fixture = fixture("phase-${faultPoint.ordinal}")
            val root = temporaryFolder.newFolder("phase-${faultPoint.ordinal}")
            val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("old-disk") }
            val vars = File(storage.parentFile, "uefi-vars.fd").also { it.writeText("old-vars") }
            val armed = AtomicBoolean(true)
            val crashing = repository(
                root, storage, MapFetcher(fixture.bytes), vars,
                ProfileRepositoryFaultInjector { point ->
                    if (point == faultPoint && armed.compareAndSet(true, false)) throw IOException("crash at $point")
                },
            )
            val prepared = crashing.prepare(envelope(fixture.profile))
            assertFailure<IOException> {
                crashing.activate(
                    prepared.candidate,
                    GuestDataPolicy.DELETE_DATA,
                    crashing.issueDataDeletionConfirmation(prepared.candidate),
                )
            }

            val restarted = repository(root, storage, MapFetcher(fixture.bytes), varsFile = vars)
            restarted.recover()
            assertEquals(prepared.candidate, restarted.activationState()!!.active)
            assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.CLOUD_DISK), storage.readBytes())
            assertArrayEquals(fixture.bytes.getValue(ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE), vars.readBytes())
            assertTrue(storage.parentFile.listFiles()!!.none { it.name.startsWith(".podroid-profile-delete-") })
        }
    }

    @Test
    fun `trust revocation at every cloud phase restores originals when retained and disables new storage`() {
        val revokedTrust = ProfileTrustPolicy(trust.trustEpoch, emptyMap())
        val faultPoints = listOf(
            ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_VARS_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_LINEAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ACTIVATION_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED,
        )
        faultPoints.forEach { faultPoint ->
            val fixture = fixture("revoke-${faultPoint.ordinal}")
            val root = temporaryFolder.newFolder("revoke-${faultPoint.ordinal}")
            val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("old-disk") }
            val vars = File(storage.parentFile, "uefi-vars.fd").also { it.writeText("old-vars") }
            val armed = AtomicBoolean(true)
            val crashing = repository(
                root, storage, MapFetcher(fixture.bytes), vars,
                ProfileRepositoryFaultInjector { point ->
                    if (point == faultPoint && armed.compareAndSet(true, false)) throw IOException("crash at $point")
                },
            )
            val prepared = crashing.prepare(envelope(fixture.profile))
            assertFailure<IOException> {
                crashing.activate(
                    prepared.candidate,
                    GuestDataPolicy.DELETE_DATA,
                    crashing.issueDataDeletionConfirmation(prepared.candidate),
                )
            }

            val revoked = repository(
                root, storage, MapFetcher(fixture.bytes), varsFile = vars, trustPolicy = revokedTrust,
            )
            revoked.recover()
            val originalsAlreadyFinalized =
                faultPoint == ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED
            if (originalsAlreadyFinalized) {
                assertFalse(storage.exists())
                assertFalse(vars.exists())
            } else {
                assertEquals("old-disk", storage.readText())
                assertEquals("old-vars", vars.readText())
            }
            assertNull(revoked.activationState())
            val failure = revoked.lastActivationFailure()!!
            assertEquals(originalsAlreadyFinalized, failure.storageDeletionIrreversible)
            assertEquals(originalsAlreadyFinalized, failure.uefiVarsDeletionIrreversible)
            assertFailure<ProfileActivationException> { revoked.resolveActiveProfile() }
        }
    }

    @Test
    fun `candidate corruption at every cloud phase restores retained originals and clears boot authority`() {
        val faultPoints = listOf(
            ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_VARS_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_LINEAGE_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ACTIVATION_PUBLISHED,
            ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED,
        )
        faultPoints.forEach { faultPoint ->
            val fixture = fixture("corrupt-${faultPoint.ordinal}")
            val root = temporaryFolder.newFolder("corrupt-${faultPoint.ordinal}")
            val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("old-disk") }
            val vars = File(storage.parentFile, "uefi-vars.fd").also { it.writeText("old-vars") }
            val armed = AtomicBoolean(true)
            val crashing = repository(
                root, storage, MapFetcher(fixture.bytes), vars,
                ProfileRepositoryFaultInjector { point ->
                    if (point == faultPoint && armed.compareAndSet(true, false)) throw IOException("crash at $point")
                },
            )
            val prepared = crashing.prepare(envelope(fixture.profile))
            assertFailure<IOException> {
                crashing.activate(
                    prepared.candidate,
                    GuestDataPolicy.DELETE_DATA,
                    crashing.issueDataDeletionConfirmation(prepared.candidate),
                )
            }
            val diskArtifact = fixture.profile.artifact(ProfileV2ArtifactRole.CLOUD_DISK)
            File(root, "store/blobs/${diskArtifact.sha256.value}.blob")
                .writeBytes(ByteArray(diskArtifact.sizeBytes.toInt()) { 0x5a })

            val restarted = repository(root, storage, MapFetcher(fixture.bytes), varsFile = vars)
            restarted.recover()
            if (faultPoint == ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED) {
                assertFalse(storage.exists())
                assertFalse(vars.exists())
                assertTrue(restarted.lastActivationFailure()!!.storageDeletionIrreversible)
                assertTrue(restarted.lastActivationFailure()!!.uefiVarsDeletionIrreversible)
            } else {
                assertEquals("old-disk", storage.readText())
                assertEquals("old-vars", vars.readText())
            }
            assertNull(restarted.activationState())
        }
    }

    @Test
    fun `missing original tombstone records exact irreversible disk loss and disables activation`() {
        val fixture = fixture("irreversible")
        val root = temporaryFolder.newFolder("irreversible-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("old-disk") }
        val vars = File(storage.parentFile, "uefi-vars.fd").also { it.writeText("old-vars") }
        val armed = AtomicBoolean(true)
        val crashing = repository(
            root, storage, MapFetcher(fixture.bytes), vars,
            ProfileRepositoryFaultInjector { point ->
                if (point == ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED &&
                    armed.compareAndSet(true, false)
                ) throw IOException("crash")
            },
        )
        val prepared = crashing.prepare(envelope(fixture.profile))
        assertFailure<IOException> {
            crashing.activate(
                prepared.candidate,
                GuestDataPolicy.DELETE_DATA,
                crashing.issueDataDeletionConfirmation(prepared.candidate),
            )
        }
        storage.parentFile.listFiles()!!.single { it.name.startsWith(".podroid-profile-delete-") }.delete()

        val revoked = repository(
            root, storage, MapFetcher(fixture.bytes), varsFile = vars,
            trustPolicy = ProfileTrustPolicy(trust.trustEpoch, emptyMap()),
        )
        revoked.recover()
        assertNull(revoked.activationState())
        assertFalse(storage.exists())
        assertEquals("old-vars", vars.readText())
        assertTrue(revoked.lastActivationFailure()!!.storageDeletionIrreversible)
        assertFalse(revoked.lastActivationFailure()!!.uefiVarsDeletionIrreversible)
    }

    @Test
    fun `cloud space failure occurs before durable intent or original mutation`() {
        val fixture = fixture("space")
        val root = temporaryFolder.newFolder("space-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("old-disk") }
        val vars = File(storage.parentFile, "uefi-vars.fd").also { it.writeText("old-vars") }
        val repository = repository(
            root, storage, MapFetcher(fixture.bytes), varsFile = vars,
            usableSpaceProvider = ProfileUsableSpaceProvider { path ->
                if (path.fileName.toString() == "blobs") Long.MAX_VALUE else 0L
            },
        )
        val prepared = repository.prepare(envelope(fixture.profile))

        assertFailure<ProfileQuotaExceededException> {
            repository.activate(
                prepared.candidate,
                GuestDataPolicy.DELETE_DATA,
                repository.issueDataDeletionConfirmation(prepared.candidate),
            )
        }
        assertEquals("old-disk", storage.readText())
        assertEquals("old-vars", vars.readText())
        assertFalse(File(root, "store/state/activation.pending").exists())
    }

    @Test
    fun `manager data removal clears cloud boot authority but preserves explicit reactivation`() {
        val fixture = fixture("remove")
        val root = temporaryFolder.newFolder("remove-root")
        val storage = File(root, "instance/storage.img").also { it.parentFile.mkdirs(); it.writeText("bundled") }
        val vars = File(storage.parentFile, "uefi-vars.fd")
        val repository = repository(root, storage, MapFetcher(fixture.bytes), varsFile = vars)
        val prepared = repository.prepare(envelope(fixture.profile))
        val initial = repository.activate(
            prepared.candidate,
            GuestDataPolicy.DELETE_DATA,
            repository.issueDataDeletionConfirmation(prepared.candidate),
        )

        repository.clearForVmRemoval()
        assertNull(repository.activationState())
        assertEquals(ActivationFailureReason.VM_DATA_REMOVED, repository.lastActivationFailure()!!.reason)
        assertFailure<ProfileActivationException> { repository.resolveActiveProfile() }
        storage.delete()
        vars.delete()
        val reactivated = repository.activate(
            prepared.candidate,
            GuestDataPolicy.DELETE_DATA,
            repository.issueDataDeletionConfirmation(prepared.candidate),
        )
        assertEquals(prepared.candidate, reactivated.active)
        assertEquals(initial.activationSequence + 1L, reactivated.activationSequence)
    }

    private fun repository(
        root: File,
        storage: File,
        fetcher: ProfileArtifactFetcher,
        varsFile: File = File(storage.parentFile, "uefi-vars.fd"),
        faultInjector: ProfileRepositoryFaultInjector = ProfileRepositoryFaultInjector { },
        usableSpaceProvider: ProfileUsableSpaceProvider = ProfileUsableSpaceProvider { Files.getFileStore(it).usableSpace },
        trustPolicy: ProfileTrustPolicy = trust,
        noCloudSeedPolicy: ProfileNoCloudSeedPolicy = ProfileNoCloudSeedPolicy { _, _ -> },
    ) = ProfileRepository(
        repositoryDirectory = File(root, "store"),
        storageFile = storage,
        approvedOrigins = origins,
        trustPolicy = trustPolicy,
        artifactFetcher = fetcher,
        directoryDurability = FileChannelDirectoryDurability,
        faultInjector = faultInjector,
        usableSpaceProvider = usableSpaceProvider,
        noCloudSeedPolicy = noCloudSeedPolicy,
        uefiVarsFile = varsFile,
    )

    private fun fixture(
        suffix: String,
        generation: Long = 1,
        compatibility: DataCompatibilityId = DataCompatibilityId("cloud-lineage-v1"),
    ): Fixture {
        val bytes = ProfileV2ArtifactRole.entries.associateWith { role ->
            "$suffix-${role.wireName}".toByteArray()
        }
        val artifacts = ProfileV2ArtifactRole.entries.map { role ->
            val value = bytes.getValue(role)
            ProfileV2Artifact(
                role,
                role.requiredFormat,
                origins.parseUrl("$ORIGIN/$suffix/${role.wireName}"),
                Sha256Digest(sha256(value)),
                value.size.toLong(),
            )
        }
        return Fixture(
            VmProfileV2(
                ProfileId("cloud-$suffix"),
                ProfileGeneration(generation),
                compatibility,
                ProfileArchitecture.AARCH64,
                VmProfileV2.BOOT_CONTRACT,
                VmProfileV2.STORAGE_CONTRACT,
                VmProfileV2.HEALTH_CONTRACT,
                ProfileV2Limits.READINESS_MARKER,
                setOf(ProfileBackend.QEMU),
                ProfileV2Capabilities.DEFAULT_DENY,
                artifacts,
            ),
            artifacts.associate { it.role to bytes.getValue(it.role) },
        )
    }

    private fun envelope(profile: VmProfileV2): ByteArray {
        val payload = ProfilePayloadV2JsonCodec.encode(profile)
        return SignedProfileEnvelopeV2JsonCodec.encode(keyId, payload, sign(ProfileSigningV2.messageFor(payload)))
    }

    private fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(keyPair.private)
        update(message)
        sign()
    }

    private class MapFetcher(private val artifacts: Map<ProfileV2ArtifactRole, ByteArray>) : ProfileArtifactFetcher {
        var calls = 0
        override fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse {
            calls++
            val role = ProfileV2ArtifactRole.entries.single { request.url.value.endsWith("/${it.wireName}") }
            val bytes = artifacts.getValue(role)
            return ArtifactFetchResponse(200, request.url.value, 0, null, bytes.size.toLong(), ByteArrayInputStream(bytes))
        }
    }

    private data class Fixture(val profile: VmProfileV2, val bytes: Map<ProfileV2ArtifactRole, ByteArray>)

    private inline fun <reified T : Throwable> assertFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private companion object {
        const val ORIGIN = "https://profiles.example:443"
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
