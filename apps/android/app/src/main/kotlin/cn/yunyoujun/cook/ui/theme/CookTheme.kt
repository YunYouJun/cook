package cn.yunyoujun.cook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cn.yunyoujun.cook.data.local.AppThemeMode

private val LeafGreenLight = Color(0xFF2E7D32)
private val LeafGreenDark = Color(0xFF81C784)

private val LightColors = lightColorScheme(
    primary = LeafGreenLight,
    onPrimary = Color.White,
    secondary = Color(0xFF526350),
    background = Color(0xFFFBFDF8),
    surface = Color(0xFFFBFDF8),
)

private val DarkColors = darkColorScheme(
    primary = LeafGreenDark,
    onPrimary = Color(0xFF0A3912),
    secondary = Color(0xFFB9CCB4),
    background = Color(0xFF111410),
    surface = Color(0xFF111410),
)

@Composable
fun CookTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
