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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import top.ccbase.campus.ui.theme.TextTint
import top.ccbase.campus.ui.theme.palette
import top.ccbase.campus.ui.theme.paletteWithAccent
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.domain.toPlanNow
import top.ccbase.campus.widget.WidgetArt
import top.ccbase.campus.widget.WidgetModel
import top.ccbase.campus.widget.buildWidgetModel
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetDyn
import top.ccbase.campus.widget.WidgetFrames
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

    Spacer(Modifier.height(14.dp))
    var showWheel by remember { mutableStateOf(AccentSpec.isDiy(spec)) }
    // 「自己调颜色」原来是个小胶囊，右边还挤一句说明，跟上面的色点视觉重量完全不搭。
    // 现在：整行一个按钮（44dp 命中区），说明单独一行放下面。
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                if (AccentSpec.isDiy(spec)) C.violet.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (AccentSpec.isDiy(spec)) C.violet.copy(alpha = 0.55f) else C.line,
                RoundedCornerShape(10.dp),
            )
            .clickable { showWheel = !showWheel },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showWheel) "收起色环" else "自己调颜色",
            fontSize = 13.sp,
            color = if (AccentSpec.isDiy(spec)) C.violet else C.txt2,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text("点一下就换色，按住拖动看效果", fontSize = 10.5.sp, color = C.txt3)
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
 * 桌面小组件的**完整预览**（4×2，按真实比例 2:1 画，不裁剪、不截半块）。
 *
 * 为什么不用 Compose 重新"仿"一个：仿的迟早和真remoteviews 走样。这里底图直接调
 * [WidgetArt.background]（真机同一函数），文字用 [buildWidgetModel] 的真实课表数据，
 * 边距/字号照 `res/layout/widget_today.xml` 的数值按屏宽等比缩放。
 *
 * 什么时候会不一样：真机上的格子宽度由 launcher 决定（MIUI 4×2≈155dp 高、原生≈170dp），
 * 窄到 210dp 以下会收起日期/周次 —— 预览按满宽画，也就是"最宽松的那种格子"。
 */
@Composable
private fun WidgetPreviewCard(
    ctx: android.content.Context,
    spec: String,
    alpha: Float = 1f,
    refreshKey: Int = 0,
) {
    val theme = remember(spec) { WidgetThemes.of(spec) }
    val density = LocalDensity.current
    var model by remember { mutableStateOf<WidgetModel?>(null) }
    LaunchedEffect(Unit) {
        model = runCatching {
            withContext(Dispatchers.IO) {
                val app = ctx.applicationContext as? CampusApplication ?: return@withContext null
                val day = java.time.LocalDate.now().toString()
                val plans = runCatching {
                    app.db.dao().plansOnce(day).map { it.toPlanNow() }
                }.getOrDefault(emptyList())
                buildWidgetModel(loadToday(app.db), 3, plans)
            }
        }.getOrNull()
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, C.lineHi, RoundedCornerShape(18.dp)),
    ) {
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(200)
        val hPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(100)
        val f = maxWidth.value / 320f            // 320dp = 常见 4×2 宽度
        // 预览也要"所见即所得"：gif/视频主题取一帧当封面，别退回成纯色渐变
        val art = remember(spec, refreshKey) {
            if (top.ccbase.campus.ui.theme.UserMedia.parse(spec) != null) {
                top.ccbase.campus.ui.theme.UserMedia.stillBitmap(
                    ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET,
                )
            } else {
                WidgetThemes.imageBitmap(ctx, theme)
            }
        }
        var bg by remember(spec, wPx, hPx, art) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(spec, wPx, hPx, refreshKey) {
            if (art == null) {
                bg = withContext(Dispatchers.IO) {
                    WidgetArt.background(
                        w = wPx, h = hPx,
                        base = theme.bgColor, accent = theme.accentColor,
                        light = theme.light,
                        radiusPx = WidgetArt.radiusFor(hPx, ctx.resources.displayMetrics.density),
                    )
                }
            }
        }
        // 预览底下的"假桌面壁纸"：不透明主题看不到它，一旦把透明度拉下来，
        // 底下这块就会透上来 —— 效果和桌面上一致，不用回桌面验证。
        Box(Modifier.fillMaxSize().background(Color(0xFF3A3550))) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF5B6FA8)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF7A5A8C)))
            }
        }
        val layer = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.coerceIn(0.05f, 1f) }
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = layer,
            )
            // 蒙版也一起跟着透明度走，否则拉透明时卡片反而更黑
            Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.coerceIn(0.05f, 1f) }
                .background(Color(0x7A000000)))
        } else {
            bg?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = layer,
                )
            }
        }

        val m = model
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = (18 * f).dp, end = (18 * f).dp,
                    top = (14 * f).dp, bottom = (11 * f).dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size((6 * f).dp).background(Color(theme.accentColor), CircleShape),
                )
                Spacer(Modifier.width((7 * f).dp))
                Text(
                    m?.status ?: "正在上课",
                    color = Color(theme.accentColor), fontSize = (11 * f).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    m?.dateLine ?: "10月18日 周六",
                    color = Color(theme.dimColor), fontSize = (10.5f * f).sp, maxLines = 1,
                )
                if ((m?.week ?: "").isNotBlank()) {
                    Spacer(Modifier.width((7 * f).dp))
                    Text(
                        m!!.week, color = Color(theme.dimColor),
                        fontSize = (10.5f * f).sp, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height((7 * f).dp))
            Text(
                m?.headline ?: "高等数学 A",
                color = Color(theme.headColor), fontSize = (19 * f).sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height((3 * f).dp))
            Text(
                m?.meta ?: "10:10–11:45 · 教A301",
                color = Color(theme.bodyColor), fontSize = (11.5f * f).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val rows = m?.rows ?: emptyList()
            if (rows.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = (9 * f).dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                rows.take(2).forEachIndexed { i, r ->
                    Spacer(Modifier.height(((if (i == 0) 7 else 5) * f).dp))
                    Text(
                        buildString {
                            append(r.time); append("　"); append(r.name)
                            if (r.room.isNotBlank()) { append(" · "); append(r.room) }
                        },
                        color = Color(theme.bodyColor), fontSize = (11.5f * f).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                m?.footer ?: "第 8 周 · 周六",
                color = Color(theme.dimColor), fontSize = (10 * f).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 「桌面小组件」那一页的全部内容（App 主题与它分开，各存各的）。
 */
@Composable
fun WidgetSection(onOpenGallery: () -> Unit = {}) {
    WidgetBlock(onOpenGallery)
}

/**
 * 「让它动起来」一排芯片（2026-10-18 用户：动态主题要像动态壁纸，不是只有粒子）。
 *
 * 同一排同时给 App 背景和桌面小组件用：点了回传 effect id（null = 静止），
 * 两边各自决定怎么落盘（App 写 `animphoto:`，小组件写 `dyni:`）。
 */
@Composable
fun MotionChips(currentEffectId: String?, onPick: (String?) -> Unit) {
    val items = listOf<Pair<String?, String>>(null to "静止") +
        WidgetFrames.ImgEffect.entries.map { it.id to it.label }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (id, label) ->
            val selected = id == currentEffectId
            Text(
                label,
                fontSize = 11.sp,
                color = if (selected) C.violet else C.txt2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) C.violet.copy(alpha = 0.16f) else C.card)
                    .border(
                        1.dp,
                        if (selected) C.violet.copy(alpha = 0.5f) else C.line,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPick(id) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
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
    // 透明度是"预览的一部分"：声明必须在预览之前，滑块拖的时候预览当场跟着变
    var wAlpha by remember { mutableFloatStateOf(WidgetThemes.alpha(ctx)) }
    // 换过图之后 spec 没变（还是 user 这条），但位图变了 —— 用这个键把预览踢一下
    var pvKey by remember { mutableIntStateOf(0) }
    var placed by remember { mutableIntStateOf(WidgetPin.placedCount(ctx)) }
    // 自动添加是否被挡住（小米/HyperOS 常见）；决定"手动步骤"要不要默认摊开
    var showManual by remember { mutableStateOf(!WidgetPin.supported(ctx)) }
    // 添加结果提示：系统确认框点「添加」后，我们等实例真的出现再报一句"成了"，
    // 而不是按按钮就假装成功（用户明确要求：加完要有添加成功的提示）。
    var pinMsg by remember { mutableStateOf<String?>(null) }
    val pinScope = rememberCoroutineScope()
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

    // ---- 先看预览：完整一张 4×2，换色/换图/拖透明度都当场反映在它身上 ----
    WidgetPreviewCard(ctx, wSpec, alpha = wAlpha, refreshKey = pvKey)
    Spacer(Modifier.height(8.dp))
    Text(
        "按 4×2 画；桌面上格子更窄时会自动收起日期/周次。",
        fontSize = 10.sp, color = C.txt3,
    )
    Spacer(Modifier.height(14.dp))

    // ---- 文字颜色（2026-10-19 用户："要支持更改app内和小控件的文字颜色，
    //      把更改文字颜色的控件放在预览旁边"）----
    // 放在预览正下方、透明度上面：这两个都是"同一张卡上的观感"，挨着改最顺手。
    // 它同时管 App 正文和小组件文字 —— 只挑一个，省得两边打架。
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("文字颜色", fontSize = 11.sp, color = C.txt3, modifier = Modifier.weight(1f))
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
                            pvKey++                     // 预览立刻重画
                            TodayWidgetProvider.refresh(ctx)  // 桌面跟着换
                        },
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    name,
                    fontSize = 9.sp,
                    color = if (picked) C.violet else C.txt3,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // ---- 主题透明度（2026-10-19 用户："希望能够调整小组件主题的透明度"）----
    // 把所有主题通用的一个滑块放在"动效"之后、"配色"之前：
    // 它管的是"这张卡有多透"，和选哪张图、哪个配色都无关。
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("主题透明度", fontSize = 11.sp, color = C.txt3, modifier = Modifier.weight(1f))
        Text(
            (wAlpha * 100).roundToInt().toString() + "%",
            fontSize = 11.sp, color = C.violet,
        )
    }
    androidx.compose.material3.Slider(
        value = wAlpha,
        onValueChange = { wAlpha = it },          // 拖的时候预览当场跟着变
        onValueChangeFinished = {
            WidgetThemes.setAlpha(ctx, wAlpha)
            TodayWidgetProvider.refresh(ctx)      // 松手推一次到桌面
        },
        valueRange = 0.35f..1f,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )

    // ---- 播放速度（2026-10-19 用户："小控件要支持更改播放速度"）----
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("播放速度", fontSize = 11.sp, color = C.txt3, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5f to "慢", 0.75f to "稍慢", 1f to "正常", 1.5f to "稍快", 2f to "快").forEach { (v, name) ->
                val on = kotlin.math.abs(WidgetThemes.speed(ctx) - v) < 0.01f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) C.violet.copy(alpha = 0.20f) else C.card)
                        .border(1.dp, if (on) C.violet.copy(alpha = 0.55f) else C.line, RoundedCornerShape(12.dp))
                        .clickable {
                            WidgetThemes.setSpeed(ctx, v)
                            WidgetFrames.clear()             // 帧按旧速度渲染的，得重做
                            pvKey++
                            TodayWidgetProvider.refresh(ctx)
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(name, color = if (on) C.violet else C.txt2, fontSize = 10.sp)
                }
            }
        }
    }
    // ---- 真 60 帧：动态壁纸（2026-10-19 用户：「小部件的主题依旧没有做到流畅播放，
    //      我希望能够做到 60 帧播放」）----
    // 实话：桌面小组件的每一帧都要跨进程把位图交给桌面，框架上不存在 60fps 这条路，
    // 硬推只会被桌面丢帧 + 耗电。能真 60 帧的只有动态壁纸 —— 同一支画笔、同一套主题。
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.card)
            .clickable {
                val comp = android.content.ComponentName(ctx, top.ccbase.campus.widget.DynWallpaperService::class.java)
                val i = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { ctx.startActivity(i) }.isSuccess
                if (!ok) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(WP_BTN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("设为动态壁纸 · 真 60 帧", fontSize = 12.5.sp, color = C.txt, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                "桌面小组件的画面要跨进程交给桌面，60 帧在框架上做不到；" +
                    "把同一支主题铺满整屏才真的每秒 60 帧。点这里在系统壁纸里确认。",
                fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt3,
            )
        }
        Text("›", fontSize = 18.sp, color = C.cyan)
    }

    Text(
        if (wAlpha >= 0.995f) "100% 是不透明；往下拉桌面壁纸会从主题里透出来。"
        else "现在能看到 ${(wAlpha * 100).roundToInt()}% 的主题，其余露出桌面壁纸。",
        fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt3,
    )


    // ---- 再给动作：把卡片放到桌面（这是这一页最常用的意图） ----
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(C.violet.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                .clickable {
                    // 系统不支持 / 被 ROM 挡掉时，直接把手动步骤摊开，别留一个点了没反应的按钮
                    val before = WidgetPin.placedCount(ctx)
                    val ok = WidgetPin.requestPin(ctx)
                    placed = before
                    pinMsg = null
                    if (!ok) {
                        showManual = true
                        pinMsg = "这台手机的桌面不让 App 直接添加，按下面 3 步手动加"
                    } else {
                        // 系统弹框 → 用户点「添加」→ 桌面真的多了一个实例，这中间有延迟，
                        // 所以轮询几次确认，确认到了才提示成功
                        pinScope.launch {
                            var waited = 0
                            while (waited < 6000) {
                                kotlinx.coroutines.delay(600)
                                waited += 600
                                val now = WidgetPin.placedCount(ctx)
                                if (now > before) {
                                    placed = now
                                    pinMsg = "已添加到桌面 ✓"
                                    return@launch
                                }
                            }
                            placed = WidgetPin.placedCount(ctx)
                            pinMsg = "没检测到新卡片；如果是你自己取消了，可再点一次"
                        }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("添加到桌面", fontSize = 12.sp, color = C.violet) }
        Spacer(Modifier.width(10.dp))
        Text(
            if (placed > 0) "桌面上已有 $placed 个" else "桌面上还没有",
            fontSize = 10.5.sp, color = C.txt3,
        )
    }
    pinMsg?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 11.sp, color = if (it.endsWith("✓")) C.violet else C.amber)
    }
    // 小米/HyperOS 需要「桌面快捷方式」权限，否则连小部件列表里都可能找不到入口
    if (WidgetPin.needsShortcutPermission()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.card, RoundedCornerShape(10.dp))
                .border(1.dp, C.line, RoundedCornerShape(10.dp))
                .clickable {
                    if (!WidgetPin.openShortcutPermission(ctx)) {
                        pinMsg = "没找到权限页，去「设置 → 应用设置 → 校园助手 → 权限管理」允许桌面快捷方式"
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("允许「桌面快捷方式」", fontSize = 12.sp, color = C.violet, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text("小米/HyperOS 需要，点一下去打开开关", fontSize = 10.sp, color = C.txt3, modifier = Modifier.weight(1f))
            Text("›", fontSize = 15.sp, color = C.txt3)
        }
    }
    Spacer(Modifier.height(12.dp))
    // 自动加到桌面在国产 ROM 上经常被挡（小米/HyperOS 尤其），所以这里不假设一定成功：
    // 一键失败就把手动步骤摊开，并给一个直达"应用信息"的口子（有些机型要先允许桌面快捷方式）。
    Text(
        if (showManual) "自动添加被这台手机的桌面挡住了，按下面的步骤手动加（3 步）。"
        else "点了没反应？点右边「手动添加」看步骤。",
        fontSize = 10.sp, lineHeight = 15.sp, color = C.txt3,
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (showManual) "收起步骤" else "手动添加",
            fontSize = 11.sp, color = C.violet,
            modifier = Modifier
                .clickable { showManual = !showManual }
                .padding(vertical = 6.dp, horizontal = 2.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            "打开应用设置",
            fontSize = 11.sp, color = C.txt3,
            modifier = Modifier
                .clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + ctx.packageName),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(vertical = 6.dp, horizontal = 2.dp),
        )
    }
    if (showManual) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.card, RoundedCornerShape(12.dp))
                .border(1.dp, C.line, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            listOf(
                "1. 回到桌面，**双指捏合**（或长按桌面空白处）进入编辑状态",
                "2. 点「小部件 / 小组件」。小米/HyperOS 的列表里它叫「**校园**」（App 的桌面名），" +
                    "按拼音排在 x 那一堆；也可以直接搜「校园」",
                "3. 按住它拖到桌面空位，松手；拖的时候可以把宽度拉成 4×2",
            ).forEach {
                Text(
                    it.replace("**", ""),
                    fontSize = 11.sp, lineHeight = 17.sp, color = C.txt2,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "小米 / HyperOS 三条路（从上往下试）：\n" +
                    "· 列表里往下滑到拼音 x（显示为「校园」），或直接搜「校园」\n" +
                    "· 列表最底下有个「安卓小部件 ›」，非小米自带的小部件都在那里\n" +
                    "· 还是没有：设置 → 应用设置 → 校园 → 权限管理，打开「桌面快捷方式」，回桌面捏合再看一次",
                fontSize = 10.5.sp, lineHeight = 16.sp, color = C.txt3,
            )
        }
    }
    // App 里传的 gif / 视频，直接挪过来当小组件的动态背景（用户："小组件同理"）。
    // 桌面播不了视频，我们抽 12 帧轮播 —— 观感上还是"在动"，但不占后台。
    val ownMedia = remember {
        top.ccbase.campus.ui.theme.UserMedia.current(ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET)
    }
    val appMedia = remember {
        top.ccbase.campus.ui.theme.UserMedia.current(ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.APP)
    }
    // 小组件自己没存过就用 App 那段（点了才复制过去，两边从此各管各的）
    val media = ownMedia ?: appMedia
    if (media != null) {
        val using = wSpec == top.ccbase.campus.ui.theme.UserMedia.spec(media.kind)
        // 2026-10-19 用户："这一块的功能指向不明"。原来只是一颗孤零零的胶囊，
        // 既没说明它属于"背景素材"，也看不出点它会做什么。现在给小标题 + 一句动作说明。
        Spacer(Modifier.height(14.dp))
        Text("我传的素材", fontSize = 11.sp, color = C.txt3)
        Spacer(Modifier.height(7.dp))
        Text(
            if (using) "✓ 桌面正在用你传的${media.kind.label}"
            else "点这里，把${media.kind.label}用到桌面这张卡",
            fontSize = 12.sp,
            color = if (wSpec == top.ccbase.campus.ui.theme.UserMedia.spec(media.kind)) C.violet else C.txt2,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(C.card)
                .clickable {
                    if (ownMedia == null) {
                        top.ccbase.campus.ui.theme.UserMedia.copyTo(
                            ctx,
                            top.ccbase.campus.ui.theme.UserMedia.Slot.APP,
                            top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET,
                        )
                    }
                    val next = top.ccbase.campus.ui.theme.UserMedia.spec(media.kind)
                    val err = WidgetThemes.apply(ctx, next)
                    if (err == null) {
                        wSpec = next
                        TodayWidgetProvider.refresh(ctx)
                        toast(ctx, "已应用到桌面")
                    } else toast(ctx, err)
                }
                .padding(horizontal = 11.dp, vertical = 8.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    Text("配色", fontSize = 11.sp, color = C.txt3)
    Spacer(Modifier.height(8.dp))

    // ---- 「随时间」：桌面唯一能做到的动态 ----
    // RemoteViews 跑不了动画，但小组件每 30 分钟会重画一次；那就让每次重画的颜色跟着
    // 窗外光线走 —— 早上偏冷蓝、午后偏暖、入夜转深紫。
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (wSpec == WidgetThemes.DYNAMIC_SPEC) C.violet.copy(alpha = 0.14f) else C.card,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (wSpec == WidgetThemes.DYNAMIC_SPEC) C.violet.copy(alpha = 0.5f) else C.line,
                RoundedCornerShape(12.dp),
            )
            .clickable {
                if (WidgetThemes.apply(ctx, WidgetThemes.DYNAMIC_SPEC) == null) {
                    wSpec = WidgetThemes.DYNAMIC_SPEC
                    TodayWidgetProvider.refresh(ctx)
                    toast(ctx, "已应用到桌面")
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "随时间",
            fontSize = 12.sp,
            color = if (wSpec == WidgetThemes.DYNAMIC_SPEC) C.violet else C.txt2,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "随一天的光线换色（每 30 分钟一次）：现在「${WidgetThemes.dynamic().name}」",
            fontSize = 10.5.sp, color = C.txt3, modifier = Modifier.weight(1f),
        )
        if (wSpec == WidgetThemes.DYNAMIC_SPEC) Text("✓", color = C.violet, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))

    // ---- 纯色预置 ----
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WidgetThemes.presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val c = if (t.id == "light") Color(0xFFF7F6FB) else Color(t.accentColor)
                    Swatch(c, wSpec == t.id, t.name) {
                        val err = WidgetThemes.apply(ctx, t.id)
                        if (err == null) { wSpec = t.id; toast(ctx, "已应用：${t.name}") } else toast(ctx, err)
                        TodayWidgetProvider.refresh(ctx)
                    }
                }
            }
        }
    }

    // 在线图库入口已经挪到这一页最上面（和「动态主题库」并排），这里不再放第二个入口 ——
    // 同一件事留两个入口，用户反而不知道点哪个。

    // ---- 上传自己的图 ----
    Spacer(Modifier.height(10.dp))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp == null) {
                toast(ctx, "这张图读不出来，换一张试试")
            } else if (WidgetThemes.saveUserImage(ctx, bmp)) {
                wSpec = WidgetThemes.USER_IMAGE_SPEC
                pvKey++
                runCatching { WidgetThemes.apply(ctx, wSpec) }.getOrNull()?.let { toast(ctx, it) }
                TodayWidgetProvider.refresh(ctx)
                toast(ctx, "已应用到桌面")
            } else {
                toast(ctx, "这张图存不下来（可能太大），换一张试试")
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


/** 一句轻提示：任何"应用到桌面"的动作都要有回音，否则用户以为按钮没响应。 */
private fun toast(ctx: android.content.Context, msg: String) {
    runCatching {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}


/** 用例抓手：动态壁纸入口 */
const val WP_BTN = "theme-wallpaper-entry"
