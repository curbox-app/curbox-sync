package neth.iecal.curboxsync

import kotlin.math.abs

// Phone to phone sync logic. A Kotlin twin of the extension's src/lib/lan/reconcile.ts and
// groups.ts, so a phone that scanned another phone's code can drive it the way a browser does.
// Kept free of Android and org.json so it runs in plain JVM tests.

data class Cycle(val focusMinutes: Int, val breakMinutes: Int, val rounds: Int, val completed: Int)

/** Focus as both sides see it, reduced to what has to agree. [phase] is null when a side cannot tell. */
data class FocusView(
    val active: Boolean,
    val groupId: String = "",
    val name: String = "",
    val endsAt: Long = 0,
    val phase: String? = null,
    val cycle: Cycle? = null,
)

object PeerReconcile {
    const val END_TOLERANCE_MS = 90_000L
    const val SETTLE_MS = 15_000L
    val INACTIVE = FocusView(false)

    /** [cycles] is true when both phones run Curbox API 7; otherwise breaks read as focus off. */
    fun view(p: PhoneFocus, now: Long, cycles: Boolean): FocusView {
        if (!p.active || p.endsAt <= now) return INACTIVE
        if (p.onBreak && !cycles) return INACTIVE
        val v = FocusView(true, p.groupId, p.name, p.endsAt)
        if (!cycles) return v
        val c = p.pomodoro ?: return v.copy(phase = "focus")
        return v.copy(
            phase = if (p.onBreak) "break" else "focus",
            cycle = Cycle(c.focusMinutes, c.breakMinutes, c.rounds, c.completed),
        )
    }

    fun desired(v: FocusView) = DesiredFocus(
        active = v.active,
        groupId = v.groupId,
        name = v.name,
        endsAt = v.endsAt,
        pomodoro = v.cycle?.let { Pomodoro(if (v.phase == "break") "BREAK" else "FOCUS", it.focusMinutes, it.breakMinutes, it.rounds, it.completed) },
    )

    fun sameGroup(aId: String, aName: String, bId: String, bName: String): Boolean =
        (aId.isNotEmpty() && aId == bId) || (aName.isNotBlank() && aName.trim().equals(bName.trim(), ignoreCase = true))

    fun sameFocus(a: FocusView, b: FocusView): Boolean {
        if (!a.active || !b.active) return a.active == b.active
        if (!sameGroup(a.groupId, a.name, b.groupId, b.name) || abs(a.endsAt - b.endsAt) > END_TOLERANCE_MS) return false
        if (a.phase == null || b.phase == null) return true
        return a.phase == b.phase && a.cycle == b.cycle
    }

    /** Around a phase or session end both phones move on their own; wait instead of pushing. */
    fun settling(v: FocusView, now: Long): Boolean = v.active && abs(v.endsAt - now) < SETTLE_MS

    enum class Action { NONE, PUSH, PULL }

    fun decide(local: FocusView, remote: FocusView, last: Pair<FocusView, FocusView>?, localChangedAt: Long, remoteChangedAt: Long): Action {
        if (sameFocus(local, remote)) return Action.NONE
        if (last == null) return if (remote.active) Action.PULL else Action.PUSH
        val localMoved = !sameFocus(local, last.first)
        val remoteMoved = !sameFocus(remote, last.second)
        return when {
            localMoved && remoteMoved -> if (localChangedAt >= remoteChangedAt) Action.PUSH else Action.PULL
            localMoved -> Action.PUSH
            remoteMoved -> Action.PULL
            else -> Action.NONE
        }
    }
}

/** What two phones share of a focus group. [apps] is null when a side does not report them. */
data class SharedGroup(val name: String, val blockMode: String, val exitable: Boolean, val websites: List<String>, val apps: List<String>?) {
    fun sameAs(o: SharedGroup): Boolean =
        name == o.name && blockMode == o.blockMode && exitable == o.exitable && websites == o.websites &&
            (apps == null || o.apps == null || apps == o.apps)

    fun edit(id: String) = GroupEdit(id, name, websites, blockMode, exitable, apps)

    companion object {
        fun clean(items: List<String>) = items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

        fun of(g: PhoneGroup) = SharedGroup(
            name = g.name.trim(),
            blockMode = g.blockMode.ifEmpty { "BLOCK_SELECTED" },
            exitable = g.exitable,
            websites = clean(g.websites.orEmpty()),
            apps = g.packages?.let(::clean),
        )
    }
}

data class GroupLink(val localId: String, val remoteId: String, val base: SharedGroup)

/** One group pair's changes. The new link is kept only if every change applied, else the old one. */
data class PairPlan(
    val newLink: GroupLink?,
    val oldLink: GroupLink?,
    val localSave: GroupEdit? = null,
    val remoteSave: GroupEdit? = null,
    val localDelete: String? = null,
    val remoteDelete: String? = null,
)

object GroupMerge {
    /** Sites (and apps) added on either side are kept, removed on either side are dropped. */
    private fun mergeSets(base: List<String>?, a: List<String>, b: List<String>): List<String> {
        if (base == null) return SharedGroup.clean(a + b)
        val had = base.toSet()
        return SharedGroup.clean((a + b).filter { (it in a && it in b) || it !in had })
    }

    fun merge(base: SharedGroup?, local: SharedGroup, remote: SharedGroup): SharedGroup {
        val apps = if (local.apps != null && remote.apps != null) mergeSets(base?.apps, local.apps, remote.apps) else local.apps ?: remote.apps
        if (base == null) return remote.copy(websites = mergeSets(null, local.websites, remote.websites), apps = apps)
        fun <T> pick(l: T, r: T, b: T) = if (l != b) l else r
        return SharedGroup(
            name = pick(local.name, remote.name, base.name),
            blockMode = pick(local.blockMode, remote.blockMode, base.blockMode),
            exitable = pick(local.exitable, remote.exitable, base.exitable),
            websites = mergeSets(base.websites, local.websites, remote.websites),
            apps = apps,
        )
    }

    fun plan(locals: List<PhoneGroup>, remotes: List<PhoneGroup>, links: List<GroupLink>?, frozen: Set<String> = emptySet()): List<PairPlan> {
        val localById = locals.associateBy { it.id }
        val remoteById = remotes.associateBy { it.id }
        var known = links.orEmpty().filter { it.localId in localById || it.remoteId in remoteById }
        // Picking another Curbox looks like every group was deleted at once; join again instead.
        val goneLocal = known.count { it.localId !in localById }
        val goneRemote = known.count { it.remoteId !in remoteById }
        if (known.size >= 2 && (goneLocal * 2 > known.size || goneRemote * 2 > known.size)) known = emptyList()

        data class Pair3(val local: PhoneGroup?, val remote: PhoneGroup?, val link: GroupLink?)
        val usedLocal = known.map { it.localId }.toMutableSet()
        val usedRemote = known.map { it.remoteId }.toMutableSet()
        val pairs = known.map { Pair3(localById[it.localId], remoteById[it.remoteId], it) }.toMutableList()
        for (l in locals) {
            if (l.id in usedLocal) continue
            val r = remotes.firstOrNull { it.id !in usedRemote && it.id == l.id }
                ?: remotes.firstOrNull { it.id !in usedRemote && l.name.isNotBlank() && it.name.trim().equals(l.name.trim(), ignoreCase = true) }
            if (r != null) usedRemote += r.id
            usedLocal += l.id
            pairs += Pair3(l, r, null)
        }
        for (r in remotes) if (r.id !in usedRemote) pairs += Pair3(null, r, null)

        return pairs.map { (local, remote, link) ->
            val base = link?.base
            val l = local?.let(SharedGroup::of)
            val r = remote?.let(SharedGroup::of)
            when {
                (local != null && local.id in frozen) || (remote != null && remote.id in frozen) -> PairPlan(link, link)
                l != null && r != null -> {
                    val merged = merge(base, l, r)
                    PairPlan(
                        newLink = GroupLink(local!!.id, remote!!.id, merged),
                        oldLink = link,
                        localSave = merged.edit(local.id).takeUnless { merged.sameAs(l) },
                        remoteSave = merged.edit(remote.id).takeUnless { merged.sameAs(r) },
                    )
                }
                l != null -> if (base != null && base.sameAs(l)) PairPlan(null, link, localDelete = local!!.id)
                else PairPlan(GroupLink(local!!.id, local.id, l), link, remoteSave = l.edit(local.id))
                else -> if (base != null && base.sameAs(r!!)) PairPlan(null, link, remoteDelete = remote!!.id)
                else PairPlan(GroupLink(remote!!.id, remote.id, r!!), link, localSave = r.edit(remote.id))
            }
        }
    }

    private fun ok(result: String?) = result == "applied" || result == "noop"

    fun settle(plans: List<PairPlan>, local: Map<String, String>, remote: Map<String, String>): List<GroupLink> = plans.mapNotNull { p ->
        val applied = (p.localSave == null || ok(local[p.localSave.id])) &&
            (p.localDelete == null || ok(local[p.localDelete])) &&
            (p.remoteSave == null || ok(remote[p.remoteSave.id])) &&
            (p.remoteDelete == null || ok(remote[p.remoteDelete]))
        if (applied) p.newLink else p.oldLink
    }

    /** True when some change was refused and has to be tried again. */
    fun refused(plans: List<PairPlan>, local: Map<String, String>, remote: Map<String, String>): Boolean = plans.any { p ->
        (p.localSave != null && !ok(local[p.localSave.id])) || (p.remoteSave != null && !ok(remote[p.remoteSave.id])) ||
            (p.localDelete != null && !ok(local[p.localDelete])) || (p.remoteDelete != null && !ok(remote[p.remoteDelete]))
    }
}
