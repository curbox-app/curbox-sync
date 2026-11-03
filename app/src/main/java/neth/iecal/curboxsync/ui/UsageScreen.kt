package neth.iecal.curboxsync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import neth.iecal.curboxsync.Source
import neth.iecal.curboxsync.UsageMath
import neth.iecal.curboxsync.UsageSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val MAX_WEEKS_BACK = 3

@Composable
fun UsageScreen(snapshot: UsageSnapshot) {
    val today = remember { LocalDate.now() }
    var weeksBack by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableIntStateOf(today.dayOfWeek.value - 1) }
    val week = UsageMath.week(weeksBack, today)
    val day = week[picked].let { if (it.isAfter(today)) today else it }
    val combined = UsageMath.combine(snapshot, day.toString())
    val bars = week.map { d ->
        val c = UsageMath.combine(snapshot, d.toString())
        Bar(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), c.phoneMs, c.browserMs)
    }
    val phoneColor = MaterialTheme.colorScheme.primary
    val browserColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)

    Column(Modifier.fillMaxWidth()) {
        Hero(
            label = if (day == today) "Total today" else day.format(DateTimeFormatter.ofPattern("EEEE, d MMM")),
            big = UsageMath.format(combined.totalMs),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(phoneColor)
            Spacer(Modifier.width(6.dp))
            Text("Phone ${UsageMath.format(combined.phoneMs)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(20.dp))
            Dot(browserColor)
            Spacer(Modifier.width(6.dp))
            Text("Browser ${UsageMath.format(combined.browserMs)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        WeekBars(
            bars = bars,
            selected = picked,
            onSelect = { i -> if (!week[i].isAfter(today)) picked = i },
            modifier = Modifier.padding(top = 32.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("‹", onClick = { if (weeksBack < MAX_WEEKS_BACK) { weeksBack++; picked = 6 } },
                color = if (weeksBack < MAX_WEEKS_BACK) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
            Text(
                weekRange(week),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            QuietButton("›", onClick = {
                if (weeksBack > 0) {
                    weeksBack--
                    picked = if (weeksBack == 0) today.dayOfWeek.value - 1 else 6
                }
            }, color = if (weeksBack > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
        }

        SectionLabel("Apps and sites")
        if (combined.rows.isEmpty()) {
            Text(
                "Nothing recorded for this day yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        combined.rows.take(40).forEach { row ->
            ListRow(
                title = row.title,
                subtitle = when {
                    row.isApp -> "App"
                    row.source == Source.BOTH -> "Website · phone and browser"
                    row.source == Source.PHONE -> "Website · phone"
                    else -> "Website · browser"
                },
                leading = { Initial(row.title) },
                trailing = { TrailingText(UsageMath.format(row.ms)) },
            )
        }

        SectionLabel("Sources")
        ListRow(
            title = "This phone",
            subtitle = when {
                snapshot.phoneReadAt == 0L -> "Not read from Curbox yet"
                !snapshot.phoneDetailed -> "Update Curbox to see apps and earlier days"
                else -> "Read from Curbox ${ago(snapshot.phoneReadAt)}"
            },
            leading = { Dot(phoneColor, 10) },
        )
        if (snapshot.browsers.isEmpty()) {
            ListRow(
                title = "No browser yet",
                subtitle = "Pair the Curbox extension to add its time here",
                leading = { Dot(browserColor, 10) },
            )
        }
        snapshot.browsers.forEach { b ->
            ListRow(
                title = b.name,
                subtitle = "Sent ${ago(b.lastSeen)} · ${b.days.size} days",
                leading = { Dot(browserColor, 10) },
            )
        }
    }
}

private fun weekRange(week: List<LocalDate>): String {
    val f = DateTimeFormatter.ofPattern("d MMM")
    return "${week.first().format(f)} to ${week.last().format(f)}"
}

fun ago(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
