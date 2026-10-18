package top.ccbase.campus.ui.me

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import top.ccbase.campus.ui.theme.ACCENT_PRESETS
import top.ccbase.campus.ui.theme.AccentSpec
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits
import top.ccbase.campus.ui.theme.palette
import top.ccbase.campus.ui.theme.paletteWithAccent
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetPin
import top.ccbase.campus.widget.WidgetThemes

/**
 * 外观页里的「主题色」与「桌面小组件」两块。
 *
 * 2026-10-18 按用户反馈重做：
 *  · **去掉「跟随壁纸」** —— 系统只给"从壁纸推出来的一个主色"，跟他要的"真的跟着壁纸变"不是一回事，
 *    留着就是骗人；
 *  · **DIY 从一根滑块换成色环**：外环选色相、内圆选浓淡，按住拖动，**整屏当场跟着变**；
 *    松手才落盘（拖动过程只改内存，不写磁盘）；
 *  · **小组件主题独立**：纯色预置 + 图片预置 + 自己上传照片（参考小米背屏那套），
 *    跟 App 内主题各存各的 —— 桌面那张要跟壁纸搭，跟 App 想用什么颜色是两件事。
 */
@Composable
fun ThemeSection() {
    val ctx = LocalContext.current
    val spec = AppearanceStore.accent.value

    val startHue = AccentSpec.diyHue(spec) ?: 265f
    val startSat = if (AccentSpec.isDiy(spec)) AccentSpec.diyTint(spec) else 1f
    var hue by remember { mutableFloatStateOf(startHue) }
    var sat by remember { mutableFloatStateOf(startSat) }
    var dragging by remember { mutableStateOf(false) }

    Heading("主题色")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ACCENT_PRESETS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val active = spec == AccentSpec.preset(p.id) ||
                        (p.id == "default" && spec == AccentSpec.DEFAULT)
                    val c = paletteWithAccent(palette, AccentSpec.preset(p.id)).violet
                    Swatch(c, active, p.name) {
                        AppearanceStore.setAccent(ctx, AccentSpec.preset(p.id))
                        hue = p.hue
                        sat = 1f
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.Top) {
        ColorWheel(
            hue = hue, sat = sat,
            onPick = { h, s ->
                hue = h
                sat = s
                dragging = true
                AppearanceStore.previewAccent(AccentSpec.diy(h, s))   // 实时：整屏当场变
            },
            onDone = {
                dragging = false
                AppearanceStore.setAccent(ctx, AccentSpec.diy(hue, sat))  // 松手才落盘
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("自己调", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = C.txt)
            Spacer(Modifier.height(4.dp))
            Text(
                "外环选颜色，往里拖变淡。按住拖动，整屏当场跟着变；松手记住。",
                fontSize = 10.5.sp, lineHeight = 16.sp, color = C.txt3,
            )
            Spacer(Modifier.height(10.dp))
            val live = paletteWithAccent(palette, AccentSpec.diy(hue, sat))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(live.violet, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("第 3~20 周", fontSize = 12.sp, color = live.violet, style = Digits)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.background(live.violet.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text("按钮文字", fontSize = 11.5.sp, color = live.violet) }
            Spacer(Modifier.height(8.dp))
            Text(if (dragging) "松手生效…" else "已记住这个颜色", fontSize = 9.5.sp, color = C.txt3)
        }
    }

    Spacer(Modifier.height(26.dp))
    Heading("桌面小组件")
    Text(
        "这里的配色**只影响桌面那张卡片**，跟上面 App 的主题各存各的。",
        fontSize = 10.5.sp, lineHeight = 16.sp, color = C.txt3,
    )
    Spacer(Modifier.height(10.dp))
    WidgetBlock()
}

/**
 * 色环：外环 = 色相，内圆 = 浓淡。按住拖动两样一起变，边拖边预览。
 */
@Composable
private fun ColorWheel(
    hue: Float,
    sat: Float,
    onPick: (hue: Float, sat: Float) -> Unit,
    onDone: () -> Unit,
) {
    val size = 158.dp
    val ringW = 15.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxR = with(density) { size.toPx() } / 2f
    val boxPx = remember { mutableStateOf(0f) }

    fun pick(pos: Offset) {
        val c = maxR
        val d = pos - Offset(c, c)
        val r = hypot(d.x, d.y)
        if (r < 1f) return
        var deg = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat() + 90f
        if (deg < 0) deg += 360f
        onPick(deg % 360f, (r / maxR).coerceIn(0.2f, 1f))
    }

    Box(
        Modifier.size(size)
            .pointerInput(Unit) { detectTapGestures { pick(it) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDone() }, onDragCancel = { onDone() }) { ch, _ ->
                    pick(ch.position)
                    ch.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            boxPx.value = this.size.width
            val r = this.size.width / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 360f).map { hsv(it, 0.85f) },
                ),
                radius = r - ringW.toPx() / 2f,
                style = Stroke(width = ringW.toPx(), cap = StrokeCap.Butt),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.92f), hsv(hue, 1f)),
                    radius = r - ringW.toPx() - 6f,
                ),
                radius = r - ringW.toPx() - 6f,
            )
            val rad = Math.toRadians((hue - 90f).toDouble())
            val rr = ((r - ringW.toPx() / 2f) * sat).coerceAtMost(r - ringW.toPx() / 2f)
            val px = center.x + (cos(rad) * rr).toFloat()
            val py = center.y + (sin(rad) * rr).toFloat()
            drawCircle(Color.White, radius = 7f, center = Offset(px, py), style = Stroke(width = 3f))
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 9.5f, center = Offset(px, py), style = Stroke(width = 1.5f))
        }
    }
}

private fun hsv(h: Float, s: Float, v: Float = 0.9f): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v)))

/**
 * 小组件那块：纯色预置 / 图片预置 / 上传自己的图 / 放到桌面。
 *
 * 「放到桌面」为什么有两套：`requestPinAppWidget` 在 MIUI 上常常"返回 true 但没真加上"，
 * 所以给一条一定能走通的长按路径，并且把当前状态（桌面上已有几个）直接写出来 ——
 * 用户自己就能看出到底成了没有。
 */
@Composable
private fun WidgetBlock() {
    val ctx = LocalContext.current
    var wSpec by remember { mutableStateOf(WidgetThemes.spec(ctx)) }
    var placed by remember { mutableIntStateOf(WidgetPin.placedCount(ctx)) }
    val darkBase = palette.bg.luminance() <= 0.5f

    // 回到前台时重算"桌面上已有几个"（用户可能刚去桌面加完回来）
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) placed = WidgetPin.placedCount(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ---- 纯色预置 ----
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WidgetThemes.presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val c = if (t.id == "light") Color(0xFFF7F6FB) else Color(t.accentColor)
                    Swatch(c, wSpec == t.id, t.name) {
                        wSpec = t.id
                        WidgetThemes.set(ctx, t.id)
                        TodayWidgetProvider.refresh(ctx)
                    }
                }
            }
        }
    }

    // ---- 图片预置 ----
    Spacer(Modifier.height(14.dp))
    Text("图片主题", fontSize = 11.5.sp, color = C.txt2)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WidgetThemes.imageThemes.forEach { t ->
            ArtThumb(
                res = t.imageRes, name = t.name, selected = wSpec == t.id,
                onClick = {
                    wSpec = t.id
                    WidgetThemes.set(ctx, t.id)
                    TodayWidgetProvider.refresh(ctx)
                },
            )
        }
    }

    // ---- 上传自己的图 ----
    Spacer(Modifier.height(10.dp))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null && WidgetThemes.saveUserImage(ctx, bmp)) {
                wSpec = WidgetThemes.USER_IMAGE_SPEC
                WidgetThemes.set(ctx, wSpec)
                TodayWidgetProvider.refresh(ctx)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.border(1.dp, if (wSpec == WidgetThemes.USER_IMAGE_SPEC) C.violet else C.line, RoundedCornerShape(9.dp))
                .clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                if (WidgetThemes.hasUserImage(ctx)) "换一张自己的图" else "上传图片",
                fontSize = 12.sp,
                color = if (wSpec == WidgetThemes.USER_IMAGE_SPEC) C.violet else C.txt2,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("从相册选，只存在这台手机上", fontSize = 10.sp, color = C.txt3)
    }

    // ---- 放到桌面 ----
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(C.violet.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                .clickable {
                    WidgetPin.requestPin(ctx)
                    placed = WidgetPin.placedCount(ctx)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("添加到桌面", fontSize = 12.sp, color = C.violet) }
        Spacer(Modifier.width(10.dp))
        Text(
            if (placed > 0) "桌面上已有 $placed 个" else "桌面上还没有",
            fontSize = 10.5.sp, color = C.txt3,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "小米/红米上系统可能点了没反应：长按桌面空白处 → 添加小部件 → 找「校园助手」→ 今日课表。加完这里会显示「桌面上已有 1 个」。",
        fontSize = 10.sp, lineHeight = 16.sp, color = C.txt3,
    )
}

@Composable
private fun ArtThumb(res: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 62.dp, height = 38.dp)
                .background(Color(0xFF23202E), RoundedCornerShape(8.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) C.txt else C.line, RoundedCornerShape(8.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(res),
                contentDescription = name,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 9.5.sp, color = if (selected) C.txt else C.txt3)
    }
}

@Composable
private fun Heading(text: String) {
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
