package com.excp.podroid.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class QmpPeerOwnershipPolicyTest {
    private val owner = QemuRuntimeOwner(QemuProcessIdentity(42, 100), 7)

    @Test fun `peer requires app uid exact owner pid and matching start ticks`() {
        val alive = ProcessIdentityObservation.Alive(owner.process)
        assertEquals(
            QmpPeerOwnershipVerdict.AUTHENTICATED,
            QmpPeerOwnershipPolicy.classify(
                owner, APP_UID, LocalSocketPeerIdentity(42, APP_UID), alive,
            ),
        )
        assertEquals(
            QmpPeerOwnershipVerdict.REJECTED,
            QmpPeerOwnershipPolicy.classify(
                owner, APP_UID, LocalSocketPeerIdentity(42, APP_UID + 1), alive,
            ),
        )
        assertEquals(
            QmpPeerOwnershipVerdict.REJECTED,
            QmpPeerOwnershipPolicy.classify(
                owner, APP_UID, LocalSocketPeerIdentity(43, APP_UID), alive,
            ),
        )
    }

    @Test fun `dead or reused exact owner cannot authenticate`() {
        assertEquals(
            QmpPeerOwnershipVerdict.DEAD_OWNER,
            QmpPeerOwnershipPolicy.classify(
                owner,
                APP_UID,
                LocalSocketPeerIdentity(42, APP_UID),
                ProcessIdentityObservation.Dead,
            ),
        )
        assertEquals(
            QmpPeerOwnershipVerdict.DEAD_OWNER,
            QmpPeerOwnershipPolicy.classify(
                owner,
                APP_UID,
                LocalSocketPeerIdentity(42, APP_UID),
                ProcessIdentityObservation.Alive(QemuProcessIdentity(42, 101)),
            ),
        )
    }

    private companion object { const val APP_UID = 10_123 }
}
