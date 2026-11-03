package neth.iecal.curboxsync

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class CurboxAccess { MISSING, CONNECTING, NEEDS_APPROVAL, READY }

data class PhoneGroup(val id: String, val name: String, val exitable: Boolean, val blockMode: String = "")

/** What Curbox on this phone is doing, as last read through the API. */
data class PhoneFocus(
    val access: CurboxAccess = CurboxAccess.CONNECTING,
    val apiVersion: Int = 0,
    val active: Boolean = false,
    val groupId: String = "",
    val name: String = "",
    val endsAt: Long = 0,
    val exitable: Boolean = true,
    val groups: List<PhoneGroup> = emptyList(),
    /** Phone clock time this state was first seen. */
    val changedAt: Long = 0,
    val rev: Long = 0,
) {
    /** Before API 4 the end is estimated from whole remaining minutes. */
    val exact: Boolean get() = apiVersion >= 4

    fun sameAs(other: PhoneFocus): Boolean =
        access == other.access && active == other.active && groupId == other.groupId &&
            name == other.name && exitable == other.exitable && groups == other.groups &&
            (!active || abs(endsAt - other.endsAt) <= if (exact) 0 else END_TOLERANCE_MS)

    fun toJson(): JSONObject = JSONObject()
        .put("connected", access == CurboxAccess.READY)
        .put("apiVersion", apiVersion)
        .put("active", active)
        .put("groupId", groupId)
        .put("name", name)
        .put("endsAt", endsAt)
        .put("exitable", exitable)
        .put("exact", exact)
        .put("changedAt", changedAt)
        .put("rev", rev)
        .put("groups", JSONArray().apply {
            groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("exitable", it.exitable).put("blockMode", it.blockMode)) }
        })

    companion object {
        const val END_TOLERANCE_MS = 90_000L
    }
}

/** The focus state the browser wants the phone to match. */
data class DesiredFocus(val active: Boolean, val groupId: String, val name: String, val endsAt: Long) {
    companion object {
        fun parse(o: JSONObject) = DesiredFocus(
            active = o.optBoolean("active"),
            groupId = o.optString("groupId").take(200),
            name = o.optString("name").take(200),
            endsAt = o.optLong("endsAt"),
        )
    }
}

/** Reads and changes Curbox's focus mode through the public AIDL API. */
class FocusBridge(private val connection: CurboxConnection) {
    private val _state = MutableStateFlow(PhoneFocus())
    val state: StateFlow<PhoneFocus> = _state
    private val applyLock = Mutex()

    /** Re-reads Curbox and bumps the revision when anything visible changed. */
    fun refresh(): PhoneFocus {
        val next = read()
        synchronized(this) {
            val prev = _state.value
            if (next.sameAs(prev)) return prev
            // A minute estimate drifts every read; keep the earlier value so it does not look like a change.
            val endsAt = if (next.active && prev.active && next.groupId == prev.groupId &&
                abs(next.endsAt - prev.endsAt) <= PhoneFocus.END_TOLERANCE_MS && !next.exact) prev.endsAt else next.endsAt
            val updated = next.copy(endsAt = endsAt, changedAt = System.currentTimeMillis(), rev = prev.rev + 1)
            _state.value = updated
            return updated
        }
    }

    private fun read(): PhoneFocus {
        val api = connection.api ?: return PhoneFocus(
            access = if (connection.packageName == null) CurboxAccess.MISSING else CurboxAccess.CONNECTING
        )
        return try {
            val version = api.apiVersion()
            if (!api.isGranted) return PhoneFocus(access = CurboxAccess.NEEDS_APPROVAL, apiVersion = version)
            val groupsJson = JSONArray(api.list("FOCUS_GROUPS") ?: "[]")
            val groups = (0 until groupsJson.length()).map {
                val g = groupsJson.getJSONObject(it)
                PhoneGroup(g.optString("id"), g.optString("name"), g.optBoolean("exitable", true), g.optString("blockMode"))
            }
            val q = JSONObject(api.query("FOCUS_ACTIVE") ?: return PhoneFocus(CurboxAccess.NEEDS_APPROVAL, version))
            val now = System.currentTimeMillis()
            val active = q.optString("active") == "true"
            if (!active) return PhoneFocus(CurboxAccess.READY, version, groups = groups)
            val name = q.optString("focus_group")
            val group = q.optString("focus_group_id").takeIf { it.isNotEmpty() }
                ?.let { id -> groups.firstOrNull { it.id == id } ?: PhoneGroup(id, name, true) }
                ?: groups.firstOrNull { it.name == name }
            val remaining = q.optString("focus_remaining").toLongOrNull() ?: 0L
            val endsAt = q.optString("focus_ends_at").toLongOrNull()?.takeIf { it > 0 }
                ?: (now + remaining * 60_000L + 30_000L)
            PhoneFocus(
                access = CurboxAccess.READY,
                apiVersion = version,
                active = true,
                groupId = group?.id.orEmpty(),
                name = name.ifEmpty { group?.name.orEmpty() },
                endsAt = endsAt,
                exitable = group?.exitable ?: true,
                groups = groups,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "Curbox call failed", e)
            PhoneFocus(access = CurboxAccess.CONNECTING)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected Curbox reply", e)
            PhoneFocus(access = CurboxAccess.CONNECTING)
        }
    }

    /**
     * Makes Curbox match [desired]. Returns one of
     * applied, noop, no_group, locked, unavailable or failed.
     */
    suspend fun apply(desired: DesiredFocus): String = applyLock.withLock {
        val current = refresh()
        val api = connection.api
        if (current.access != CurboxAccess.READY || api == null) return "unavailable"
        val now = System.currentTimeMillis()
        val result = try {
            if (desired.active && desired.endsAt > now + 30_000L) {
                val group = current.groups.firstOrNull { it.id == desired.groupId }
                    ?: current.groups.firstOrNull { it.name.trim().equals(desired.name.trim(), ignoreCase = true) }
                when {
                    group == null -> "no_group"
                    current.active && current.groupId == group.id &&
                        abs(current.endsAt - desired.endsAt) <= PhoneFocus.END_TOLERANCE_MS -> "noop"
                    else -> {
                        val minutes = ((desired.endsAt - now + 59_999L) / 60_000L).toInt().coerceIn(1, 24 * 60)
                        val status = api.execute("START_FOCUS", Bundle().apply {
                            putString("target", group.id)
                            putInt("minutes", minutes)
                            putLong("ends_at", desired.endsAt)
                        })
                        statusResult(status, current)
                    }
                }
            } else if (current.active) {
                statusResult(api.execute("STOP_FOCUS", Bundle()), current)
            } else "noop"
        } catch (e: RemoteException) {
            Log.w(TAG, "Curbox call failed", e)
            "unavailable"
        }
        refresh()
        result
    }

    private fun statusResult(status: String?, before: PhoneFocus) = when (status) {
        "OK" -> "applied"
        "FAILED" -> if (before.active && !before.exitable) "locked" else "failed"
        "DENIED" -> "unavailable"
        else -> "failed"
    }

    companion object {
        private const val TAG = "FocusBridge"
    }
}
