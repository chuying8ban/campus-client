package top.ccbase.campus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌 —— 色值**逐条从网页版 `static/style.css` 的 `:root` 搬过来**。
 *
 * 为什么不自己另配一套：原生版和网页版以后要长期并存（网页版是兜底通道），
 * 两套配色只要有一点偏差，你一眼就看得出来"这不是同一个 App"。
 * 所以真相源就是网页版那份 CSS，改配色两边一起改。
 */
object C {
    // 底（深底不留纯黑）
    val bg = Color(0xFF0B0910)          // --bg
    val bgSoft = Color(0xFF12101C)      // --bg-soft
    val card = Color(0x0AFFFFFF)        // --card       rgba(255,255,255,.038)
    val cardHi = Color(0x0FFFFFFF)      // --card-hi    rgba(255,255,255,.058)
    val line = Color(0x13FFFFFF)        // --line       rgba(255,255,255,.075)
    val lineHi = Color(0x24FFFFFF)      // --line-hi    rgba(255,255,255,.14)

    // 字（三级层次）
    val txt = Color(0xFFEAE7F4)         // --txt
    val txt2 = Color(0xFFA49DBD)        // --txt-2
    val txt3 = Color(0xFF6F6889)        // --txt-3

    // 语义色（状态档位：绿 / 琥珀 / 红）
    val violet = Color(0xFFA06BFF)      // 主色
    val magenta = Color(0xFFFF5FA2)
    val cyan = Color(0xFF4FE3D0)
    val amber = Color(0xFFFFB454)       // 可解的问题（如时间冲突）
    val red = Color(0xFFFF6B6B)         // 硬错
    val green = Color(0xFF57D98A)       // 完成
}

private val CampusColors = darkColorScheme(
    primary = C.violet,
    onPrimary = C.bg,
    secondary = C.cyan,
    onSecondary = C.bg,
    tertiary = C.magenta,
    background = C.bg,
    onBackground = C.txt,
    surface = C.bgSoft,
    onSurface = C.txt,
    surfaceVariant = C.bgSoft,
    onSurfaceVariant = C.txt2,
    outline = C.line,
    error = C.red,
    onError = C.bg,
)

/**
 * 数字一律等宽 —— 百分比、周次、余额、时间并排时不跳字。
 * 网页版也是这条规矩（`font-variant-numeric: tabular-nums`）。
 */
val Digits: TextStyle = TextStyle(fontFamily = FontFamily.Monospace)

private val CampusType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
    )
}

private val CampusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),   // --r: 16px
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CampusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CampusColors,
        typography = CampusType,
        shapes = CampusShapes,
        content = content,
    )
}
