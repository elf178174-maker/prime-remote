package dev.primeremote.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Background = Color(0xFF101318)
val Surface1 = Color(0xFF181C23)
val Surface2 = Color(0xFF222832)
val Accent = Color(0xFF4C6EF5)
val AccentSoft = Color(0xFF6C8BFF)
val Good = Color(0xFF2ECC71)
val Warn = Color(0xFFF39C12)
val Bad = Color(0xFFE74C3C)
val TextDim = Color(0xFF93A1B5)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentSoft,
    onSecondary = Color.White,
    background = Background,
    onBackground = Color(0xFFE8ECF3),
    surface = Surface1,
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    error = Bad,
)

private val typography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
)

@Composable
fun PrimeRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
