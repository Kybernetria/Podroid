package com.excp.podroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCommandOrderTest {
    @Test
    fun `earlier binder start delivered after later direct stop is stale`() {
        val order = ServiceCommandOrder()
        val start = order.reserve()
        order.executeDirect { }

        assertFalse(order.deliver(start).execute)
    }

    @Test
    fun `earlier binder restart delivered after later direct stop is stale`() {
        val order = ServiceCommandOrder()
        val restart = order.reserve()
        order.executeDirect { }

        assertFalse(order.deliver(restart).execute)
    }

    @Test
    fun `duplicate reserved lifecycle delivery executes exactly once`() {
        val order = ServiceCommandOrder()
        val start = order.reserve()

        assertTrue(order.deliver(start).execute)
        assertFalse(order.deliver(start).execute)
    }

    @Test
    fun `direct stop initiates before a later binder start can reserve`() {
        val order = ServiceCommandOrder()
        val events = mutableListOf<String>()

        order.executeDirect { events += "stop" }
        val start = order.reserve()
        order.deliverAndExecute(start) { events += "start" }

        assertTrue(events == listOf("stop", "start"))
    }

    @Test
    fun `newer reservation makes an undelivered restart stale`() {
        val order = ServiceCommandOrder()
        val restart = order.reserve()
        order.reserve()

        assertFalse(order.deliver(restart).execute)
    }

    @Test
    fun `unsequenced notification command becomes newest at delivery`() {
        val order = ServiceCommandOrder()
        val delayedBinderStart = order.reserve()

        assertTrue(order.deliver(reservedGeneration = null).execute)
        assertFalse(order.deliver(delayedBinderStart).execute)
    }

    @Test
    fun `explicit generation can be adopted after service process state recreation`() {
        val order = ServiceCommandOrder()

        assertTrue(order.deliver(42L).execute)
        assertFalse(order.deliver(42L).execute)
        assertFalse(order.deliver(41L).execute)
    }
}
