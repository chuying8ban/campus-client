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
 * 文字颜色选择器 —— **App 主题页和小组件主题页共用同一个控件、同一份偏好**。
 *
 * 2026-10-19 用户："更改文字的功能怎么没有出现" —— 上一版只挂在了小组件主题页，
 * 而他在「App 主题」这一页找，自然找不到。同一个东西摆两处，但只有一份存储
 * （[TextTint]），改哪边都是整机一致。
 */
@Composable
fun TextTintPicker(
    ctx: Context,
    title: String = "文字颜色",
    onChange: () -> Unit = {},
) {
    var tick by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 11.sp, color = C.txt3, modifier = Modifier.weight(1f))
        Text(
            TextTint.options.firstOrNull { it.first == TextTint.state.value }?.second ?: "默认",
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
            val picked = TextTint.state.value == hex
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
                            TextTint.set(ctx, hex)
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
    Text(
        // 深底配浅字、浅底配深字；黑/白都给了，是为了浅色主题或亮照片底下能手动定死。
        "这排颜色同时管 App 里的字和桌面小组件的字。字看不清时先来这里换一个。",
        fontSize = 10.sp, lineHeight = 15.sp, color = C.txt3, fontWeight = FontWeight.Normal,
    )
}
