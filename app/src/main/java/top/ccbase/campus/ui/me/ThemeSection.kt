package top.ccbase.campus.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.ACCENT_PRESETS
import top.ccbase.campus.ui.theme.AccentSpec
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.WallpaperAccent
import top.ccbase.campus.ui.theme.paletteWithAccent
import top.ccbase.campus.ui.theme.palette
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetThemes

/**
 * 外观页里的「主题色」与「小组件配色」两块。
 *
 * 三条设计取舍：
 *  ① 主题**不改底板**，只转强调色的色相（见 `Accents.kt` 的说明）—— 这样「大量主题」
 *     不会把深浅两套已经验过对比度的令牌搞坏；
 *  ② DIY 只给一个**色相**滑块，亮度写死。让人连亮度一起调，只会调出一屏看不清的字；
 *  ③ 小组件与 App 的主题**分开存**：桌面卡片要和壁纸搭，跟 App 里想用什么颜色是两件事。
 */
@Composable
fun ThemeSection() {
    val ctx = LocalContext.current
    val spec = AppearanceStore.accent.value
    val darkBase = palette.bg.luminance() <= 0.5f
    val wallpaperOk = remember { WallpaperAccent.supported() }

    // 当前色相：预置/壁纸时跟着规格走；DIY 拖动时用本地值即时预览
    var diyHue by remember(spec) {
        mutableFloatStateOf(AccentSpec.diyHue(spec) ?: 0f)
    }

    ThemeHeading("主题色")
    Spacer(Modifier.height(8.dp))
    // 一排 12 个色球：点一下整屏换色，立即生效
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ACCENT_PRESETS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val active = spec == AccentSpec.preset(p.id) ||
                        (p.id == "default" && spec == AccentSpec.DEFAULT)
                    val base = paletteWithAccent(palette, AccentSpec.preset(p.id))
                    Swatch(
                        color = base.violet,
                        selected = active,
                        label = p.name,
                        onClick = { AppearanceStore.setAccent(ctx, AccentSpec.preset(p.id)) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    // 跟随壁纸：只取色相（取不到壁纸图片），系统不给就置灰并说明
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 120.dp, height = 34.dp)
                .border(1.dp, if (spec == AccentSpec.WALLPAPER) C.violet else C.line, RoundedCornerShape(9.dp))
                .clickable(enabled = wallpaperOk) {
                    AppearanceStore.setAccent(ctx, AccentSpec.WALLPAPER)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "跟随壁纸",
                fontSize = 12.sp,
                color = when {
                    !wallpaperOk -> C.txt3.copy(alpha = 0.5f)
                    spec == AccentSpec.WALLPAPER -> C.violet
                    else -> C.txt2
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (wallpaperOk) "按壁纸的主色调自动配色"
            else "这台手机/系统不支持读壁纸颜色",
            fontSize = 10.5.sp, color = C.txt3,
        )
    }

    Spacer(Modifier.height(14.dp))
    Text("自己调", fontSize = 12.sp, color = C.txt2)
    Spacer(Modifier.height(2.dp))
    Text("拖动选色相，松手生效；亮度固定，保证字看得清。", fontSize = 10.5.sp, color = C.txt3)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth().height(10.dp).background(
            Brush.horizontalGradient(
                listOf(
                    Color(0xFFFF5A5A), Color(0xFFFFB454), Color(0xFF57D98A),
                    Color(0xFF4FE3D0), Color(0xFF6FA8FF), Color(0xFFA06BFF), Color(0xFFFF5FA2), Color(0xFFFF5A5A),
                ),
            ),
            RoundedCornerShape(5.dp),
        ),
    )
    Slider(
        value = diyHue,
        onValueChange = {
            diyHue = it
            // 拖动时只改内存，不动磁盘
            AppearanceStore.previewAccent(AccentSpec.diy(it, 1f))
        },
        onValueChangeFinished = {
            AppearanceStore.setAccent(ctx, AccentSpec.diy(diyHue, 1f))
        },
        valueRange = 0f..360f,
        colors = SliderDefaults.colors(
            thumbColor = C.txt,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        ),
    )

    Spacer(Modifier.height(22.dp))
    ThemeHeading("桌面小组件")
    Spacer(Modifier.height(8.dp))
    val widgetSpec = remember { WidgetThemes.spec(ctx) }
    var wSpec by remember { mutableStateOf(widgetSpec) }
    var wHue by remember { mutableFloatStateOf(wSpec.removePrefix("diy:").substringBefore(":").toFloatOrNull() ?: 0f) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WidgetThemes.presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val c = if (t.id == "light") Color(0xFFF7F6FB) else Color(t.accentColor)
                    Swatch(
                        color = c,
                        selected = wSpec == t.id,
                        label = t.name,
                        onClick = {
                            wSpec = t.id
                            WidgetThemes.set(ctx, t.id)
                            // 立刻刷新桌面那张卡，不用等系统 30 分钟
                            TodayWidgetProvider.refresh(ctx)
                        },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("小组件色相", fontSize = 11.5.sp, color = C.txt2)
    Slider(
        value = wHue,
        onValueChange = { wHue = it },
        onValueChangeFinished = {
            wSpec = AccentSpec.diy(wHue, if (darkBase) 1f else 0f)
            WidgetThemes.set(ctx, wSpec)
            TodayWidgetProvider.refresh(ctx)
        },
        valueRange = 0f..360f,
        colors = SliderDefaults.colors(thumbColor = C.txt, activeTrackColor = C.violet, inactiveTrackColor = C.line),
    )
    Text(
        "自己调色的小组件在 Android 12 以上由系统裁圆角；更老的系统是直角。" +
            "小组件配色与 App 主题分开保存 —— 桌面那张要跟壁纸搭，跟 App 里想用什么颜色是两件事。",
        fontSize = 10.sp, lineHeight = 16.sp, color = C.txt3,
    )
}

@Composable
private fun ThemeHeading(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = C.txt2)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(46.dp)) {
        Box(
            Modifier.size(30.dp)
                .background(color, CircleShape)
                .border(if (selected) 2.dp else 1.dp, if (selected) C.txt else C.line, CircleShape)
                .clickable { onClick() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = 9.5.sp,
            color = if (selected) C.txt else C.txt3,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
