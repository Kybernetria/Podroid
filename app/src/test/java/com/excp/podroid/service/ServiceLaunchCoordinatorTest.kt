package com.excp.podroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLaunchCoordinatorTest {
    @Test
    fun `stop before manager start entry invalidates the exact queued owner`() {
        val coordinator = ServiceLaunchCoordinator<Any>()
        val queuedJob = Any()
        val launch = requireNotNull(coordinator.beginLaunch(queuedJob))

        val stop = coordinator.beginStop()

        assertTrue(stop.shouldExecute)
        assertSame(queuedJob, stop.launchOwner)
        assertTrue(stop.generation > launch.generation)
        assertFalse(coordinator.completeLaunch(launch.generation))
        assertTrue(coordinator.ownershipActive.value)
    }

    @Test
    fun `stop during acceptance remains authoritative over launch completion`() {
        val coordinator = ServiceLaunchCoordinator<Any>()
        val acceptingJob = Any()
        val launch = requireNotNull(coordinator.beginLaunch(acceptingJob))

        val stop = coordinator.beginStop()
        val duplicateStop = coordinator.beginStop()

        assertSame(acceptingJob, stop.launchOwner)
        assertFalse(duplicateStop.shouldExecute)
        assertNull(duplicateStop.launchOwner)
        assertFalse(coordinator.completeLaunch(launch.generation))
        assertTrue(coordinator.completeStop(stop.generation))
        assertFalse(coordinator.ownershipActive.value)
    }

    @Test
    fun `stale completion from old generation cannot clear new ownership`() {
        val coordinator = ServiceLaunchCoordinator<Any>()
        val oldLaunch = requireNotNull(coordinator.beginLaunch(Any()))
        val stop = coordinator.beginStop()
        assertTrue(coordinator.completeStop(stop.generation))
        val newLaunch = requireNotNull(coordinator.beginLaunch(Any()))

        assertFalse(coordinator.completeLaunch(oldLaunch.generation))
        assertTrue(coordinator.ownershipActive.value)
        assertTrue(coordinator.completeLaunch(newLaunch.generation))
        assertFalse(coordinator.ownershipActive.value)
    }

    @Test
    fun `start after completed stop receives a fresh generation`() {
        val coordinator = ServiceLaunchCoordinator<String>()
        val first = requireNotNull(coordinator.beginLaunch("first"))
        val stop = coordinator.beginStop()

        assertTrue(coordinator.completeStop(stop.generation))
        val second = requireNotNull(coordinator.beginLaunch("second"))

        assertEquals("second", second.owner)
        assertTrue(second.generation > stop.generation)
        assertTrue(stop.generation > first.generation)
    }
}
