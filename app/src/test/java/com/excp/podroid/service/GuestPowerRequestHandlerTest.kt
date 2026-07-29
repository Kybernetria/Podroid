package com.excp.podroid.service

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.hostbridge.HostProtocol
import com.excp.podroid.engine.hostbridge.HostRequestDispatcher
import com.excp.podroid.engine.hostbridge.NotificationPoster
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleOutcome
import com.excp.podroid.vm.LifecycleTransactionToken
import com.excp.podroid.vm.VmDesiredState
import com.excp.podroid.vm.VmLifecycleState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPowerRequestHandlerTest {
    @Test
    fun `dispatcher returns OK only after guest restart desired state is pending and scheduled`() = runBlocking {
        val order = ServiceCommandOrder()
        var durableId = 0L
        var desiredState = VmDesiredState.STOPPED
        var outcome: LifecycleOutcome? = null
        var scheduled: LifecycleTransactionToken? = null
        val handler = GuestPowerRequestHandler(
            lifecycle = { VmLifecycleState.RUNNING },
            admitAndSchedule = { operation, schedule ->
                order.admitAndDispatch(
                    latestDurableGeneration = { durableId },
                    prepare = { generation ->
                        val token = LifecycleTransactionToken.restore(generation, operation, 0L)
                        durableId = generation
                        desiredState = VmDesiredState.RUNNING
                        outcome = LifecycleOutcome.PENDING
                        token
                    },
                    dispatch = { admission ->
                        assertEquals(VmDesiredState.RUNNING, desiredState)
                        assertEquals(LifecycleOutcome.PENDING, outcome)
                        schedule(admission.prepared)
                    },
                ).prepared
            },
            schedule = { scheduled = it },
            admissionFailed = { throw AssertionError("unexpected admission failure", it) },
        )

        val response = dispatcher(handler).handle("POWER restart")

        assertEquals("OK", response)
        assertEquals(VmDesiredState.RUNNING, desiredState)
        assertEquals(LifecycleOutcome.PENDING, outcome)
        assertEquals(LifecycleOperation.RESTART, scheduled?.operation)
        assertEquals(durableId, scheduled?.id)
    }

    @Test
    fun `failed guest power persistence returns ERR and schedules no effect`() = runBlocking {
        var scheduled: LifecycleTransactionToken? = null
        var observedFailure: Throwable? = null
        val handler = GuestPowerRequestHandler(
            lifecycle = { VmLifecycleState.RUNNING },
            admitAndSchedule = { _, _ -> throw IOException("store unavailable") },
            schedule = { scheduled = it },
            admissionFailed = { observedFailure = it },
        )

        val response = dispatcher(handler).handle("POWER stop")

        assertTrue(response.startsWith("ERR "))
        assertEquals("power command admission failed", HostProtocol.dec(response.removePrefix("ERR ")))
        assertNull(scheduled)
        assertTrue(observedFailure is IOException)
    }

    private fun dispatcher(handler: GuestPowerRequestHandler) = HostRequestDispatcher(
        notifications = object : NotificationPoster {
            override fun notificationsPermitted() = true
            override fun post(title: String?, body: String, priority: String, id: Int?) = 1
        },
        addForward = { _: PortForwardRule -> },
        removeForward = { _: PortForwardRule -> },
        listForwards = { emptyList() },
        openUrl = { HostProtocol.ok() },
        power = handler::handle,
        setHeadless = { HostProtocol.ok() },
    )
}
