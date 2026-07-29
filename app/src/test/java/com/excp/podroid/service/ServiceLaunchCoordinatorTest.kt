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
        assertNull(coordinator.completeStop(stop.generation, Any())?.launch)
        assertFalse(coordinator.ownershipActive.value)
    }

    @Test
    fun `stale completion from old generation cannot clear new ownership`() {
        val coordinator = ServiceLaunchCoordinator<Any>()
        val oldLaunch = requireNotNull(coordinator.beginLaunch(Any()))
        val stop = coordinator.beginStop()
        assertNull(coordinator.completeStop(stop.generation, Any())?.launch)
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

        assertNull(coordinator.completeStop(stop.generation, "unused")?.launch)
        val second = requireNotNull(coordinator.beginLaunch("second"))

        assertEquals("second", second.owner)
        assertTrue(second.generation > stop.generation)
        assertTrue(stop.generation > first.generation)
    }

    @Test
    fun `start after stop before completion launches once with a fresh generation`() {
        val coordinator = ServiceLaunchCoordinator<String>()
        val first = requireNotNull(coordinator.beginLaunch("first"))
        val stop = coordinator.beginStop()

        assertTrue(coordinator.queueStartDuringStop())
        assertTrue(coordinator.queueStartDuringStop())
        val completion = requireNotNull(coordinator.completeStop(stop.generation, "queued"))
        val queued = requireNotNull(completion.launch)

        assertEquals("queued", queued.owner)
        assertTrue(queued.generation > stop.generation)
        assertTrue(stop.generation > first.generation)
        assertTrue(coordinator.ownershipActive.value)
        assertNull(coordinator.completeStop(stop.generation, "duplicate"))
        assertTrue(coordinator.completeLaunch(queued.generation))
        assertFalse(coordinator.ownershipActive.value)
    }

    @Test
    fun `restart intent survives stale cancelled-generation completion window`() {
        val coordinator = ServiceLaunchCoordinator<String>()
        val cancelled = requireNotNull(coordinator.beginLaunch("cancelled"))
        val stop = coordinator.beginRestart()

        assertFalse(coordinator.completeLaunch(cancelled.generation))
        assertNull(coordinator.completeStop(cancelled.generation, "stale"))
        val restart = requireNotNull(
            requireNotNull(coordinator.completeStop(stop.generation, "restart")).launch,
        )

        assertEquals("restart", restart.owner)
        assertTrue(restart.generation > stop.generation)
        assertTrue(coordinator.ownershipActive.value)
    }
}
