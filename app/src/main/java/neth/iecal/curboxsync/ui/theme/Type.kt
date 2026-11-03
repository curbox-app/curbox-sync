package neth.iecal.curboxsync.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import neth.iecal.curboxsync.R

/** Large stats and headers, like Curbox. */
val Coolvetica = FontFamily(Font(R.font.coolvetica_rg))
/** Everything else. */
val Inter = FontFamily(Font(R.font.inter_regular))

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Coolvetica, fontSize = 55.sp, lineHeight = 58.sp),
    displayMedium = base.displayMedium.copy(fontFamily = Coolvetica, fontSize = 40.sp, lineHeight = 44.sp),
    displaySmall = base.displaySmall.copy(fontFamily = Coolvetica, fontSize = 30.sp, lineHeight = 34.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Coolvetica),
    headlineMedium = base.headlineMedium.copy(fontFamily = Coolvetica),
    headlineSmall = base.headlineSmall.copy(fontFamily = Coolvetica),
    titleLarge = base.titleLarge.copy(fontFamily = Inter),
    titleMedium = base.titleMedium.copy(fontFamily = Inter),
    titleSmall = base.titleSmall.copy(fontFamily = Inter),
    bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = base.bodySmall.copy(fontFamily = Inter),
    labelLarge = base.labelLarge.copy(fontFamily = Inter),
    labelMedium = base.labelMedium.copy(fontFamily = Inter),
    labelSmall = base.labelSmall.copy(fontFamily = Inter, letterSpacing = 0.8.sp),
)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 2.sp)
