package neth.iecal.curboxsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLogicTest {
    private val now = 1_000_000L

    private fun phone(active: Boolean, pomodoro: Pomodoro? = null, api: Int = 7) = PhoneFocus(
        access = CurboxAccess.READY, apiVersion = api, active = active, groupId = "g1", name = "Study",
        endsAt = now + 300_000, pomodoro = pomodoro,
    )

    private fun group(id: String, name: String, sites: List<String>, apps: List<String> = emptyList()) =
        PhoneGroup(id, name, true, "BLOCK_SELECTED", apps.size, sites, apps)

    @Test
    fun breaksReadAsOffWithoutCycles() {
        val onBreak = phone(true, Pomodoro("BREAK", 25, 5, 4, 1))
        assertFalse(PeerReconcile.view(onBreak, now, cycles = false).active)
        val v = PeerReconcile.view(onBreak, now, cycles = true)
        assertEquals("break", v.phase)
        assertEquals(Cycle(25, 5, 4, 1), v.cycle)
    }

    @Test
    fun cyclesMustMatch() {
        val a = PeerReconcile.view(phone(true, Pomodoro("FOCUS", 25, 5, 4, 1)), now, true)
        val b = PeerReconcile.view(phone(true, Pomodoro("FOCUS", 25, 5, 4, 2)), now, true)
        assertFalse(PeerReconcile.sameFocus(a, b))
        assertTrue(PeerReconcile.sameFocus(a, a.copy(endsAt = a.endsAt + 30_000)))
        assertEquals("FOCUS", PeerReconcile.desired(a).pomodoro?.phase)
    }

    @Test
    fun decidesLikeTheExtension() {
        val off = PeerReconcile.INACTIVE
        val on = PeerReconcile.view(phone(true), now, true)
        assertEquals(PeerReconcile.Action.PULL, PeerReconcile.decide(off, on, null, 0, 0))
        assertEquals(PeerReconcile.Action.PUSH, PeerReconcile.decide(on, off, off to off, 10, 0))
        assertEquals(PeerReconcile.Action.NONE, PeerReconcile.decide(on, off, on to off, 0, 0))
        assertTrue(PeerReconcile.settling(on.copy(endsAt = now + 5_000), now))
    }

    @Test
    fun mergesSitesAndApps() {
        val base = SharedGroup("Study", "BLOCK_SELECTED", true, listOf("a.com", "b.com"), listOf("com.x"))
        val local = base.copy(websites = listOf("a.com", "c.com"), apps = listOf("com.x", "com.y"))
        val remote = base.copy(websites = listOf("a.com", "b.com", "d.com"), apps = emptyList())
        val merged = GroupMerge.merge(base, local, remote)
        assertEquals(listOf("a.com", "c.com", "d.com"), merged.websites)
        assertEquals(listOf("com.y"), merged.apps)
    }

    @Test
    fun copiesNewGroupsBothWays() {
        val plans = GroupMerge.plan(listOf(group("a", "Read", listOf("r.com"))), listOf(group("b", "Work", listOf("w.com"), listOf("com.slack"))), null)
        assertEquals("a", plans.mapNotNull { it.remoteSave }.single().id)
        val local = plans.mapNotNull { it.localSave }.single()
        assertEquals("b", local.id)
        assertEquals(listOf("com.slack"), local.apps)
    }

    @Test
    fun deletesOnlyAgreedGroupsAndGuardsWipes() {
        val groups = listOf(group("a", "A", listOf()), group("b", "B", listOf()), group("c", "C", listOf()))
        val links = groups.map { GroupLink(it.id, it.id, SharedGroup.of(it)) }
        val one = GroupMerge.plan(groups, groups.take(2), links)
        assertEquals(listOf("c"), one.mapNotNull { it.localDelete })
        val wiped = GroupMerge.plan(groups, emptyList(), links)
        assertTrue(wiped.none { it.localDelete != null })
        assertEquals(3, wiped.count { it.remoteSave != null })
    }

    @Test
    fun keepsTheOldLinkWhenAChangeIsRefused() {
        val g = group("a", "A", listOf("a.com"))
        val link = GroupLink("a", "a", SharedGroup.of(g))
        val plans = GroupMerge.plan(listOf(g.copy(websites = listOf("a.com", "b.com"))), listOf(g), listOf(link))
        assertEquals(listOf(link), GroupMerge.settle(plans, emptyMap(), mapOf("a" to "locked")))
        assertTrue(GroupMerge.refused(plans, emptyMap(), mapOf("a" to "locked")))
        assertEquals(listOf("a.com", "b.com"), GroupMerge.settle(plans, emptyMap(), mapOf("a" to "applied")).single().base.websites)
        assertNull(GroupMerge.plan(listOf(g), listOf(g), listOf(link)).single().remoteSave)
    }
}
