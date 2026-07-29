/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.EngineHolder.Companion.computeRuleDiff
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure port-forward diff used by EngineHolder's reconciliation loop.
 *
 * The load-bearing property is the cold-start seeding: when the VM enters
 * Running, appliedRules is seeded from the launch set (the rules already baked
 * into QEMU's cmdline). An unchanged boot must therefore produce no work
 * (added == removed == empty), and a rule removed during the boot window must
 * still be torn down on Running.
 */
class EngineHolderDiffTest {

    private fun rule(host: Int, guest: Int = host) = PortForwardRule(host, guest)

    @Test
    fun `unchanged boot yields no add and no remove`() {
        val launch = setOf(rule(8080), rule(2222, 22))
        // desired == launch set (nothing changed during boot)
        val (added, removed) = computeRuleDiff(applied = launch, desired = launch)
        assertEquals(emptySet<PortForwardRule>(), added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `rule removed mid-boot is torn down`() {
        val launch = setOf(rule(8080), rule(2222, 22))
        val desired = setOf(rule(8080)) // user removed 2222 during boot
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(emptySet<PortForwardRule>(), added)
        assertEquals(setOf(rule(2222, 22)), removed)
    }

    @Test
    fun `rule added mid-boot is applied`() {
        val launch = setOf(rule(8080))
        val desired = setOf(rule(8080), rule(9090)) // user added 9090 during boot
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(setOf(rule(9090)), added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `empty applied re-adds everything (legacy seeding regression guard)`() {
        // The pre-fix seeding (appliedRules = emptySet) re-added every baked-in
        // rule on Running; this documents that contrast so the seeding choice is
        // intentional, not accidental.
        val desired = setOf(rule(8080), rule(2222, 22))
        val (added, removed) = computeRuleDiff(applied = emptySet(), desired = desired)
        assertEquals(desired, added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }

    @Test
    fun `launch-only ssh is never removed`() {
        // Enabled SSH is injected into the launch set but not persisted as a user
        // rule. The diff loop folds that launch-only rule back into desired.
        val ssh = rule(9922, 22)
        val launch = setOf(ssh)                          // appliedRules at →Running
        val persisted = emptySet<PortForwardRule>()      // DataStore: no user rules
        val implicit = launch - persisted                // captured at →Running edge
        val desired = persisted + implicit               // the fold
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(emptySet<PortForwardRule>(), removed)
        assertEquals(emptySet<PortForwardRule>(), added)
    }

    @Test
    fun `user rule removed mid-session is torn down while launch-only ssh survives`() {
        val ssh = rule(9922, 22)
        val user = rule(8000)
        val launch = setOf(ssh, user)
        val implicit = launch - setOf(user)             // = {ssh}, captured at →Running
        // user later deletes 8000 → DataStore now empty
        val desired = emptySet<PortForwardRule>() + implicit
        val (added, removed) = computeRuleDiff(applied = launch, desired = desired)
        assertEquals(setOf(user), removed)              // user rule gone; ssh not removed
        assertEquals(emptySet<PortForwardRule>(), added)
    }

    @Test
    fun `former display and audio ports are ordinary explicit rules`() {
        val explicit = setOf(rule(5900), rule(4713))
        val (added, removed) = computeRuleDiff(applied = emptySet(), desired = explicit)
        assertEquals(explicit, added)
        assertEquals(emptySet<PortForwardRule>(), removed)
    }
}
