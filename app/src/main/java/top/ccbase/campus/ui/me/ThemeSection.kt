package top.ccbase.campus.ui.me

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
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
fun AccentSection() {
    val ctx = LocalContext.current
    val spec = AppearanceStore.accent.value
    // 色点必须从**没有叠过强调色的底板**算起。用 palette（= 已叠过当前强调色）再转一次，
    // 色相会被转两遍，于是"默认紫"显示成土黄、"青叶"显示成橄榄绿 —— 2026-10-18 用户看到的就是这个。
    val basePalette = if (palette.bg.luminance() <= 0.5f) {
        top.ccbase.campus.ui.theme.DarkPalette
    } else {
        top.ccbase.campus.ui.theme.LightPalette
    }

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
                    val c = paletteWithAccent(basePalette, AccentSpec.preset(p.id)).violet
                    Swatch(c, active, p.name) {
                        AppearanceStore.setAccent(ctx, AccentSpec.preset(p.id))
                        hue = p.hue
                        sat = 1f
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    var showWheel by remember { mutableStateOf(AccentSpec.isDiy(spec)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.border(
                1.dp,
                if (AccentSpec.isDiy(spec)) C.violet else C.line,
                RoundedCornerShape(9.dp),
            ).clickable { showWheel = !showWheel }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                if (showWheel) "收起色环" else "自己调颜色",
                fontSize = 12.sp,
                color = if (AccentSpec.isDiy(spec)) C.violet else C.txt2,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("点一下就换色，按住拖动看效果", fontSize = 10.sp, color = C.txt3)
    }
    if (!showWheel) return

    val live = paletteWithAccent(basePalette, AccentSpec.diy(hue, sat))

    // 一屏宽度的 62%，最大 260dp：手机竖屏上正好，横屏/平板上也不会挤成一条
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val wheelSize = minOf(260.dp, screenW * 0.62f)

    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ColorWheel(
            hue = hue, sat = sat, size = wheelSize,
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
    }

    // 选中的结果必须**当场看得见**：色点 + 色号 + 浓淡百分比。
    // 只有色环在转、没有读数时，手指挡住的地方等于没有反馈。
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(live.violet, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "#%06X".format(0xFFFFFF and live.violet.toArgb()),
                fontSize = 12.5.sp, color = C.txt, style = Digits,
            )
            Text(
                "浓淡 ${(sat * 100).toInt()}% · " + if (dragging) "松手生效…" else "已记住",
                fontSize = 10.sp, color = C.txt3,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.background(live.violet.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("第 3~20 周", fontSize = 11.5.sp, color = live.violet) }
    }

    // 手指点不到环上的窄条时，这排是兜底：8 个色相一点就中
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { h0 ->
            val on = kotlin.math.abs(((hue - h0 + 540f) % 360f) - 180f) > 172f
            Box(
                Modifier.size(34.dp)
                    .clip(CircleShape)
                    .background(hsv(h0, 1f))
                    .border(if (on) 2.dp else 0.dp, if (on) Color.White else Color.Transparent, CircleShape)
                    .clickable {
                        hue = h0
                        AppearanceStore.setAccent(ctx, AccentSpec.diy(h0, sat))
                    },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "色环：外圈转色相，往里拖变淡；拖到哪整屏变到哪。",
        fontSize = 10.sp, lineHeight = 15.sp, color = C.txt3,
    )
}

/**
 * 「桌面小组件」那一页的全部内容（App 主题与它分开，各存各的）。
 */
@Composable
fun WidgetSection(onOpenGallery: () -> Unit = {}) {
    WidgetBlock(onOpenGallery)
}

/**
 * 色环：外环 = 色相，内圆 = 浓淡。按住拖动两样一起变，边拖边预览。
 */
@Composable
private fun ColorWheel(
    hue: Float,
    sat: Float,
    size: Dp,
    onPick: (hue: Float, sat: Float) -> Unit,
    onDone: () -> Unit,
) {
    val ringW = 26.dp          // 原来 15dp，环带太窄、手指对不准
    val ringGrab = 20.dp       // 环带外侧再多给一圈"算你点中环"的容差
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var lastBucket by remember { mutableIntStateOf(-1) }

    fun emit(pos: Offset) {
        val w = box.width.toFloat()
        if (w <= 0f) return
        val c = w / 2f
        val dx = pos.x - c
        val dy = pos.y - c
        val r = hypot(dx, dy)
        val ringPx = with(density) { ringW.toPx() }
        val grabPx = with(density) { ringGrab.toPx() }
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (deg < 0) deg += 360f
        deg %= 360f
        // 点在环带（含容差）→ 只改色相，浓淡保持不动；点在内圆 → 色相+浓淡一起改
        val inRing = r >= (c - ringPx - grabPx)
        val newSat = if (inRing) sat else (r / (c - ringPx)).coerceIn(0.15f, 1f)
        onPick(deg, newSat)
        // 每转过 12° 抖一下：手指按着屏幕时看不见指针，靠手感确认"在变"
        val bucket = (deg / 12f).toInt()
        if (bucket != lastBucket) {
            lastBucket = bucket
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        Modifier.size(size)
            .onSizeChanged { box = it }
            .pointerInput(box) {
                // 不用 detectDragGestures：它有"先超过触摸阈值才算拖动"的等待期，
                // 手指一按先竖向划一下就会被外层 ScrollColumn 抢走（用户反馈"拖不动/页面在滚"）。
                // 这里按下就吃掉事件，直到抬起 —— 环内整块区域归我。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    emit(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        emit(ch.position)
                        ch.consume()
                    }
                    onDone()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.width / 2f
            val ringPx = ringW.toPx()
            // 外圈：色相
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 360f).map { hsv(it, 0.85f) },
                ),
                radius = r - ringPx / 2f,
                style = Stroke(width = ringPx, cap = StrokeCap.Butt),
            )
            // 内圆：从白到当前色相的径向渐变 = 浓淡（中心最淡）
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(hsv(hue, 0.10f), hsv(hue, 0.92f)),
                    radius = r - ringPx - 3f,
                ),
                radius = r - ringPx - 3f,
            )
            // 环上指针：一根短白线，比圆点更好认
            val rad = Math.toRadians((hue - 90f).toDouble())
            val ringR = r - ringPx / 2f
            val hx = center.x + (cos(rad) * ringR).toFloat()
            val hy = center.y + (sin(rad) * ringR).toFloat()
            drawCircle(Color.White, radius = 9f, center = Offset(hx, hy), style = Stroke(width = 3f))
            // 内圆落点
            val dotR = ((r - ringPx) * sat).coerceIn(6f, r - ringPx)
            val px = center.x + (cos(rad) * dotR).toFloat()
            val py = center.y + (sin(rad) * dotR).toFloat()
            drawCircle(Color.White, radius = 8f, center = Offset(px, py), style = Stroke(width = 3f))
            drawCircle(Color.Black.copy(alpha = 0.45f), radius = 10f, center = Offset(px, py), style = Stroke(width = 1.5f))
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
private fun WidgetBlock(onOpenGallery: () -> Unit) {
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

    // ---- 先给动作：把卡片放到桌面（这是这一页最常用的意图） ----
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
    Spacer(Modifier.height(12.dp))
    Text(
        "点了没反应就长按桌面空白处 → 小部件 → 「校园助手」→ 今日课表。",
        fontSize = 10.sp, lineHeight = 15.sp, color = C.txt3,
    )
    Spacer(Modifier.height(16.dp))
    Text("配色", fontSize = 11.sp, color = C.txt3)
    Spacer(Modifier.height(8.dp))

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

    // 原来这里有 4 张"图片主题"，其实是程序画的渐变色块，用户一看就知道不是图（"怎么还是渐变色"）。
    // 现在图走「在线图库」（服务器上真实插画，可搜索），这里只留纯色 + 图库 + 自己上传。

    // ---- 在线图库 ----
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.border(1.dp, C.line, RoundedCornerShape(9.dp))
                .clickable { onOpenGallery() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("在线图库", fontSize = 12.sp, color = C.txt2) }
        Spacer(Modifier.width(10.dp))
        Text("插画底图，可搜索", fontSize = 10.sp, color = C.txt3)
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
