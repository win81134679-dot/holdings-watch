package tw.personal.holdings.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun HoldingsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 台灣習慣漲紅跌綠：正數紅、負數綠、0 用一般字色。 */
@Composable
fun trendColor(sign: Int): Color {
    val dark = isSystemInDarkTheme()
    return when {
        sign > 0 -> if (dark) Color(0xFFFF6E6E) else Color(0xFFC62828)
        sign < 0 -> if (dark) Color(0xFF5CD68A) else Color(0xFF1B7F3B)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
