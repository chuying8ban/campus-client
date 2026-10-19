package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 文字颜色选择器 —— App 主题页和小组件主题页各摆一个，但**互不影响**。
 *
 * 2026-10-19 用户："文字颜色要只改自己的那部分，app主题那里的只改app的，
 * 小控件那里的只改小控件的"。所以这里不是"同一份设置摆两处"，而是两个独立设置：
 *  - `widget = false`（App 主题页）→ 只写 `text_tint`，只改 App 里的字；
 *  - `widget = true`（小组件页）→ 只写 `widget_text_tint`，只改桌面卡片上的字。
 */
@Composable
fun TextTintPicker(
    ctx: Context,
    widget: Boolean = false,
    title: String = if (widget) "小组件文字颜色" else "App 文字颜色",
    onChange: () -> Unit = {},
) {
    var tick by remember { mutableIntStateOf(0) }
    val current = (if (widget) TextTint.widgetState else TextTint.state).value
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 11.sp, color = C.txt3, modifier = Modifier.weight(1f))
        Text(
            TextTint.options.firstOrNull { it.first == current }?.second ?: "默认",
            fontSize = 11.sp, color = C.violet,
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextTint.options.forEach { (hex, name) ->
            val picked = current == hex
            val swatch = TextTint.parse(hex) ?: C.txt
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            if (picked) 2.dp else 1.dp,
                            if (picked) C.violet else C.line,
                            CircleShape,
                        )
                        .clickable {
                            if (widget) TextTint.setWidget(ctx, hex) else TextTint.set(ctx, hex)
                            tick++
                            onChange()
                        },
                )
                Spacer(Modifier.height(3.dp))
                Text(name, fontSize = 9.sp, color = if (picked) C.violet else C.txt3)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    // 撞色提示：不拦着他选，只把后果说清楚（2026-10-19：他挑黑字后以为"背景被改了"，
    // 其实是上一版压幕太狠；现在不压纯色底板，那就有必要提前说一句）
    val chosen = TextTint.parse(current)
    val lowContrast = chosen != null && TextTint.contrast(chosen, C.bg) < 2.0f
    if (lowContrast) {
        Text(
            if (widget) "这个颜色在现在的卡片底色上会很淡 —— 换个颜色或换个主题更清楚"
            else "这个颜色在现在的主题底色上会很淡 —— 换个颜色或换个主题更清楚",
            fontSize = 10.sp, color = C.amber, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(3.dp))
    }
    Text(
        if (widget) {
            "只改桌面卡片上的字，不影响 App 里的文字"
        } else {
            "只改 App 里的字，不影响桌面小组件。背景会自动压一层幕保证看得清。"
        },
        fontSize = 10.sp, lineHeight = 15.sp, color = C.txt3, fontWeight = FontWeight.Normal,
    )
}
