package neth.iecal.curboxsync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class UsageMathTest {
    private val day = "2026-11-03"

    @Test
    fun mergesSitesFromBothDevices() {
        val snapshot = UsageSnapshot(
            phone = mapOf(day to PhoneDay(day, 3_600_000, listOf(AppTime("a.b", "Chat", 1_800_000)), listOf(SiteTime("www.YouTube.com", 600_000)))),
            browsers = listOf(BrowserUsage("x", "Chrome", 0, mapOf(day to BrowserDay(day, 1_200_000, listOf(SiteTime("youtube.com", 900_000), SiteTime("news.org", 300_000)))))),
        )
        val c = UsageMath.combine(snapshot, day)
        assertEquals(4_800_000, c.totalMs)
        assertEquals(listOf("Chat", "youtube.com", "news.org"), c.rows.map { it.title })
        assertEquals(Source.BOTH, c.rows[1].source)
        assertEquals(1_500_000, c.rows[1].ms)
        assertEquals(Source.BROWSER, c.rows[2].source)
    }

    @Test
    fun addsOtherPhones() {
        val snapshot = UsageSnapshot(
            phone = mapOf(day to PhoneDay(day, 3_600_000, listOf(AppTime("a.b", "Chat", 1_800_000)), emptyList())),
            phones = listOf(PeerPhoneUsage("p2", "Pixel", 0, mapOf(day to PhoneDay(day, 1_000_000, listOf(AppTime("a.b", "", 600_000), AppTime("c.d", "Maps", 100_000)), listOf(SiteTime("news.org", 50_000)))))),
        )
        val c = UsageMath.combine(snapshot, day)
        assertEquals(4_600_000, c.phoneMs)
        assertEquals(listOf("Chat", "Maps", "news.org"), c.rows.map { it.title })
        assertEquals(2_400_000, c.rows[0].ms)
        assertEquals(Source.PHONE, c.rows[2].source)
        assertEquals(4_600_000L to 0L, UsageMath.totals(snapshot, day))
    }

    @Test
    fun splitsBySourceInStableOrder() {
        val snapshot = UsageSnapshot(
            phone = mapOf(day to PhoneDay(day, 100, emptyList(), emptyList())),
            phones = listOf(
                PeerPhoneUsage("z", "Moto", 0, mapOf(day to PhoneDay(day, 30, emptyList(), emptyList()))),
                PeerPhoneUsage("a", "Pixel", 0, mapOf(day to PhoneDay(day, 20, emptyList(), emptyList()))),
            ),
            browsers = listOf(BrowserUsage("x", "Chrome", 0, mapOf(day to BrowserDay(day, 7, emptyList())))),
        )
        assertEquals(listOf(100L, 20L, 30L, 7L), UsageMath.bySource(snapshot, day))
    }

    @Test
    fun weekStartsOnMonday() {
        val week = UsageMath.week(1, LocalDate.parse("2026-11-04"))
        assertEquals(LocalDate.parse("2026-10-26"), week.first())
        assertEquals(LocalDate.parse("2026-11-01"), week.last())
    }

    @Test
    fun formatsDurations() {
        assertEquals("0m", UsageMath.format(0))
        assertEquals("<1m", UsageMath.format(10_000))
        assertEquals("2h", UsageMath.format(7_200_000))
        assertEquals("1h 5m", UsageMath.format(3_900_000))
    }

    @Test
    fun prunesOldDays() {
        val days = mapOf("2026-09-01" to BrowserDay("2026-09-01", 1, emptyList()), day to BrowserDay(day, 1, emptyList()))
        assertEquals(setOf(day), UsageMath.prune(days, LocalDate.parse(day)).keys)
    }

    @Test
    fun subnetMasksAddress() {
        assertEquals("192.168.1.0", Networks.subnetOf("192.168.1.37", 24))
        assertEquals("10.0.0.0", Networks.subnetOf("10.0.3.9", 22))
    }
}
