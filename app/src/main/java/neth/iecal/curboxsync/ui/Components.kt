package neth.iecal.curboxsync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val Gutter = 20.dp

/** The small label over a big Coolvetica number, like Curbox's usage screens. */
@Composable
fun Hero(label: String, big: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 56.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(big, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
    )
}

/** A flat row with no card, like Curbox's app usage items. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun TrailingText(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A rounded tile with the first letter, standing in for an icon. */
@Composable
fun Initial(text: String, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.trim().firstOrNull()?.uppercase() ?: "·",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier.height(52.dp),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

/** Two words at the top, the selected one solid. */
@Composable
fun Tabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val bg by animateColorAsState(
                if (i == selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent, label = "tab",
            )
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

data class Bar(val label: String, val phone: Long, val browser: Long)

/** Weekly bars split into phone and browser time, like Curbox's WeeklyBarGraphView. */
@Composable
fun WeekBars(bars: List<Bar>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val phoneColor = MaterialTheme.colorScheme.primary
    val browserColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    val faded = 0.45f
    val max = bars.maxOfOrNull { it.phone + it.browser }?.coerceAtLeast(1L) ?: 1L
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).pointerInput(bars.size) {
                detectTapGestures { p -> onSelect((p.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex)) }
            },
        ) {
            val slot = size.width / bars.size
            val w = slot * 0.42f
            val r = CornerRadius(8.dp.toPx())
            val minH = 4.dp.toPx()
            bars.forEachIndexed { i, b ->
                val x = slot * i + (slot - w) / 2
                val alpha = if (i == selected) 1f else faded
                val total = b.phone + b.browser
                if (total == 0L) {
                    drawRoundRect(phoneColor.copy(alpha = 0.12f), Offset(x, size.height - minH), Size(w, minH), r)
                    return@forEachIndexed
                }
                val h = (size.height * total / max).coerceAtLeast(minH)
                val ph = h * b.phone / total
                drawRoundRect(browserColor.copy(alpha = browserColor.alpha * alpha), Offset(x, size.height - h), Size(w, h), r)
                if (ph > 0f) drawRoundRect(phoneColor.copy(alpha = alpha), Offset(x, size.height - ph), Size(w, ph), r)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            bars.forEachIndexed { i, b ->
                Text(
                    b.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
