package neth.iecal.curboxsync.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The Curbox app palette (curbox/app/src/main/res/values*/colors.xml).
val LightColors = lightColorScheme(
    primary = Color(0xFF016B5D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E1),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E1),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF83D5C5),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF9FF2E1),
    secondary = Color(0xFFB1CCC5),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCDE8E1),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
)

/** Other phones' chart colors, picked to sit beside the teal of this phone. */
val PeerColorsLight = listOf(Color(0xFFC77D1A), Color(0xFF6D5BD0), Color(0xFFC4505E), Color(0xFF2E7DB8))
val PeerColorsDark = listOf(Color(0xFFF2B866), Color(0xFFB7AAF7), Color(0xFFF29AA5), Color(0xFF8CC4EE))
