package neth.iecal.curboxsync

import java.time.LocalDate

data class AppTime(val packageName: String, val label: String, val ms: Long, val launches: Int = 0)
data class SiteTime(val domain: String, val ms: Long)

/** One day as Curbox on this phone recorded it. */
data class PhoneDay(val date: String, val screenMs: Long, val apps: List<AppTime>, val sites: List<SiteTime>)

/** One day as a browser recorded it. */
data class BrowserDay(val date: String, val totalMs: Long, val sites: List<SiteTime>)

data class BrowserUsage(val id: String, val name: String, val lastSeen: Long, val days: Map<String, BrowserDay>)

data class UsageSnapshot(
    val phone: Map<String, PhoneDay> = emptyMap(),
    /** False when Curbox is older than API 5 and only shares today's total. */
    val phoneDetailed: Boolean = true,
    val phoneReadAt: Long = 0,
    val browsers: List<BrowserUsage> = emptyList(),
)

enum class Source { PHONE, BROWSER, BOTH }

data class UsageRow(val key: String, val title: String, val isApp: Boolean, val ms: Long, val source: Source)

data class CombinedDay(
    val date: String,
    val phoneMs: Long,
    val browserMs: Long,
    val rows: List<UsageRow>,
) {
    val totalMs: Long get() = phoneMs + browserMs
}

object UsageMath {
    const val RETAIN_DAYS = 28

    fun combine(snapshot: UsageSnapshot, date: String): CombinedDay {
        val phone = snapshot.phone[date]
        val browserDays = snapshot.browsers.mapNotNull { it.days[date] }
        val apps = phone?.apps.orEmpty().map { UsageRow("app:${it.packageName}", it.label.ifBlank { it.packageName }, true, it.ms, Source.PHONE) }
        val sites = LinkedHashMap<String, Pair<Long, Long>>()
        phone?.sites.orEmpty().forEach { s ->
            val d = cleanDomain(s.domain)
            val (p, b) = sites[d] ?: (0L to 0L)
            sites[d] = (p + s.ms) to b
        }
        browserDays.flatMap { it.sites }.forEach { s ->
            val d = cleanDomain(s.domain)
            val (p, b) = sites[d] ?: (0L to 0L)
            sites[d] = p to (b + s.ms)
        }
        val siteRows = sites.map { (d, t) ->
            val source = when {
                t.first > 0 && t.second > 0 -> Source.BOTH
                t.first > 0 -> Source.PHONE
                else -> Source.BROWSER
            }
            UsageRow("site:$d", d, false, t.first + t.second, source)
        }
        return CombinedDay(
            date = date,
            phoneMs = phone?.screenMs ?: 0L,
            browserMs = browserDays.sumOf { it.totalMs },
            rows = (apps + siteRows).filter { it.ms > 0 }.sortedByDescending { it.ms },
        )
    }

    /** Monday to Sunday of the week [weeksBack] weeks before this one. */
    fun week(weeksBack: Int, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(weeksBack.toLong())
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    fun prune(days: Map<String, BrowserDay>, today: LocalDate = LocalDate.now()): Map<String, BrowserDay> {
        val cutoff = today.minusDays(RETAIN_DAYS.toLong()).toString()
        return days.filterKeys { it > cutoff }
    }

    fun isDate(s: String): Boolean = DATE.matches(s)

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun cleanDomain(d: String) = d.trim().lowercase().removePrefix("www.")

    fun format(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes <= 0L -> if (ms > 0) "<1m" else "0m"
            minutes < 60L -> "${minutes}m"
            minutes % 60L == 0L -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
