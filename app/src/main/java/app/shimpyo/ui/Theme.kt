package app.shimpyo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 차량 화면은 어두운 배경, 높은 대비를 쓴다(Pleos 설계 원칙: 큰 터치 영역, 높은 대비, 최소 텍스트). */
private val Colors = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF00281A),
    secondary = Color(0xFFFFC98A),
    onSecondary = Color(0xFF2B1700),
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFE8ECEF),
    surface = Color(0xFF1A1F23),
    onSurface = Color(0xFFE8ECEF),
    surfaceVariant = Color(0xFF262D33),
    onSurfaceVariant = Color(0xFFB9C3CB),
)

@Composable
fun ShimpyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
