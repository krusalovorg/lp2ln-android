package lp2ln_android.krusalov.org.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Colors = lightColorScheme(
    primary = Color(0xFF3478F6),
    onPrimary = Color.White,
    background = Color(0xFFF5F5F9),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF83858C),
    surfaceVariant = Color(0xFFECECF0),
    outline = Color(0xFFD3D4DA),
    error = Color(0xFFC64040),
)
@Composable
fun Lp2lnandroidTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = Colors.background.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
