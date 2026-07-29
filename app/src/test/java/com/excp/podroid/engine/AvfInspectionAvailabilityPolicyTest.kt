package com.excp.podroid.engine

import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.RuntimeProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfInspectionAvailabilityPolicyTest {
    @Test fun `feature absence is absent but unavailable inspection permission is uncertain`() {
        assertEquals(
            RuntimeProbeResult.Absent,
            AvfInspectionAvailabilityPolicy.classify(
                featurePresent = false,
                inspectionPermissionGranted = false,
            ),
        )
        val denied = AvfInspectionAvailabilityPolicy.classify(
            featurePresent = true,
            inspectionPermissionGranted = false,
        )
        assertTrue(denied is RuntimeProbeResult.Uncertain)
        denied as RuntimeProbeResult.Uncertain
        assertEquals(LifecycleErrorCode.SECURITY, denied.errorCode)
        assertTrue(denied.runtimeMayBeLive)
        assertNull(AvfInspectionAvailabilityPolicy.classify(true, true))
    }
}
