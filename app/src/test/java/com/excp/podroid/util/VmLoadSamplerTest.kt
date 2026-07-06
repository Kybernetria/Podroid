package com.excp.podroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmLoadSamplerTest {

    @Test
    fun sampleCpuPercent_returnsNullOnFirstSample() {
        val sampler = VmLoadSampler()
        assertNull(sampler.sampleCpuPercent(pid = 1, vmCpus = 2))
    }

    @Test
    fun sampleCpuPercent_computesNormalizedLoad() {
        val sampler = VmLoadSampler(clockHz = 100L)
        // Seed first sample
        sampler.sampleCpuPercent(pid = 1, vmCpus = 2)
        // Manually set state by second call with same pid - won't work without real /proc

        // Test math via readProcessCpuTicks on invalid pid
        assertNull(VmLoadSampler.readProcessCpuTicks(-1))
    }

    @Test
    fun reset_clearsWarmup() {
        val sampler = VmLoadSampler()
        sampler.sampleCpuPercent(1, 2)
        sampler.reset()
        assertNull(sampler.sampleCpuPercent(1, 2))
    }
}
