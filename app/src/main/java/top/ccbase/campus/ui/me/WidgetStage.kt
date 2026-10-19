package top.ccbase.campus.ui.me

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.domain.toPlanNow
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetFrames
import top.ccbase.campus.widget.WidgetModel
import top.ccbase.campus.widget.WidgetThemes
import top.ccbase.campus.widget.buildWidgetModel

/**
 * 桌面小组件的**预览**与**自检**，共用这一套（2026-10-19 用户：
 * "我希望预览和自检用同一套系统，把它们合并一下"）。
 *
 * 合并前是两套东西：
 *  · 预览 = 用 Compose 手画一张"像桌面"的卡（自己算边距、字号、行数）；
 *  · 自检 = 把真 RemoteViews 在 App 里 apply 一遍。
 * 结果是同一张卡在 App 里有两个长得不一样的样子 —— 手画的那张永远"比桌面好看"，
 * 于是桌面上真出问题时，预览还在骗人。这正是用户反复报"小控件和预览不一样"的根子。
 *
 * 现在只有一条路：[WidgetStageCard] 直接把**桌面那份 RemoteViews**（同 `viewsSized`）
 * 按 launcher 报的真实格子尺寸 apply 出来。预览是它，自检也是它；自检只剩读数。
 */

/** 桌面实例（launcher 报的那个格子）。 */
data class WidgetInstance(val id: Int, val wDp: Int, val hDp: Int)

/**
 * 「桌面事实」：预览与自检都从这里取，谁都不许自己再算一遍尺寸/行数。
 *
 * @property model   真实课表数据（和桌面同一个 [buildWidgetModel]）。
 * @property deskW/deskH launcher 报给这张卡的格子 dp（没有实例时是典型 4×2）。
 * @property rows    按可用高度算出来的行数（[TodayWidgetProvider.rowsFor]）。
 * @property wPx/hPx 渲染像素 = 格子 dp × 屏幕密度 —— RemoteViews 就按这个尺寸画。
 */
data class WidgetStageInfo(
    val model: WidgetModel?,
    val deskW: Float,
    val deskH: Float,
    val rows: Int,
    val wPx: Int,
    val hPx: Int,
    val instances: List<WidgetInstance>,
)

/** 读一次桌面事实。刷新键变了（换主题/换图/回到前台）就重读。 */
@Composable
fun rememberWidgetStageInfo(ctx: Context, refreshKey: Int = 0): WidgetStageInfo {
    val desk = remember(refreshKey) { TodayWidgetProvider.deskSizeDp(ctx) }
    val rows = TodayWidgetProvider.rowsFor(TodayWidgetProvider.usableHDp(desk.second))
    val density = ctx.resources.displayMetrics.density
    val wPx = (desk.first * density).toInt().coerceAtLeast(200)
    val hPx = (desk.second * density).toInt().coerceAtLeast(120)
    val instances = remember(refreshKey) { widgetInstances(ctx) }
    var model by remember { mutableStateOf<WidgetModel?>(null) }
    LaunchedEffect(rows, refreshKey) {
        model = runCatching {
            withContext(Dispatchers.IO) {
                val app = ctx.applicationContext as? CampusApplication ?: return@withContext null
                val day = java.time.LocalDate.now().toString()
                val plans = runCatching {
                    app.db.dao().plansOnce(day).map { it.toPlanNow() }
                }.getOrDefault(emptyList())
                buildWidgetModel(loadToday(app.db), rows, plans)
            }
        }.getOrNull()
    }
    return WidgetStageInfo(model, desk.first, desk.second, rows, wPx, hPx, instances)
}

/** 桌面上现在我们这个小组件的实例（没有就是空列表）。 */
fun widgetInstances(ctx: Context): List<WidgetInstance> {
    val mgr = AppWidgetManager.getInstance(ctx)
    val ids = runCatching {
        mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
    }.getOrDefault(IntArray(0))
    return ids.map { id ->
        val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
        WidgetInstance(
            id,
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
            o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
        )
    }
}

/**
 * 那张卡。**桌面怎么画，这里就怎么画**：真 RemoteViews、真尺寸、真比例、真透明度。
 *
 * @param spec  主题规格（可以是"试穿"用的临时规格，不写回设置）。
 * @param alpha 透明度。量化到 1/20 再渲染：滑块拖动时不至于每一帧都重画位图，
 *              松手落盘后按真值再画一次（20 档肉眼看不出台阶）。
 */
@Composable
fun WidgetStageCard(
    info: WidgetStageInfo,
    spec: String,
    alpha: Float,
    modifier: Modifier = Modifier,
    /** 换文字颜色/换字体这类"规格没变、但画面要变"的改动，靠它把卡片踢一下重建。 */
    refreshKey: Int = 0,
) {
    val ctx = LocalContext.current
    val aq = ((alpha.coerceIn(0.05f, 1f) * 20f).roundToInt() / 20f)
    // 圆角必须和位图里那个**同一个数**（WidgetArt.radiusFor），再按"这张卡显示得比
    // 桌面那一格大几倍"等比放大：位图是拉伸铺满的，圆角也跟着拉伸，Compose 的裁切
    // 要是不跟着放大，就把桌面的圆角又切小一圈（用户会直接说"预览还是方的"）。
    val density = ctx.resources.displayMetrics.density
    val radiusDp = (top.ccbase.campus.widget.WidgetArt.radiusFor(info.hPx, density) / density).dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio((info.deskW / info.deskH).coerceIn(1.2f, 2.6f)),
    ) {
        val shownDp = maxWidth
        val scale = (shownDp.value / info.deskW).coerceAtLeast(0.5f)
        val shape = RoundedCornerShape(radiusDp * scale)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .border(1.dp, C.lineHi, shape),
        ) {
            // 底下的"假桌面壁纸"：不透明主题看不到它，透明度拉下来时它才透上来 ——
            // 效果和真桌面一致，不用回桌面验证。
            DeskBackdrop()
            // model 必须进 key：卡片是 AndroidView，factory 只跑一次。
            // 首帧 model 还是 null（课表在 IO 里读），只按尺寸做 key 的话，数据回来后
            // 视图不会重建 —— 真机上就是"有图没字"（Robolectric 里 model 是现成的，测不出来）。
            key(spec, info.wPx, info.hPx, aq, info.model, refreshKey) {
                AndroidView(
                    factory = { c -> FrameLayout(c).also { mountWidgetViews(it, c, info, spec, aq) } },
                    update = { },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DeskBackdrop() {
    // 假桌面壁纸：**不能是两块纯色**（真机上 50% 透明时中间会出现一条笔直的接缝，
    // 看着像渲染坏了）。用一层斜向渐变 + 两团柔光，看起来像一张照片，又没有硬边。
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color(0xFF23212F), Color(0xFF3A3050), Color(0xFF4B3A5E)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        val r1 = size.width * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x55E9C6FF), Color(0x00E9C6FF)),
                center = Offset(size.width * 0.22f, size.height * 0.30f),
                radius = r1,
            ),
            radius = r1,
            center = Offset(size.width * 0.22f, size.height * 0.30f),
        )
        val r2 = size.width * 0.45f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x446B8FD6), Color(0x006B8FD6)),
                center = Offset(size.width * 0.85f, size.height * 0.85f),
                radius = r2,
            ),
            radius = r2,
            center = Offset(size.width * 0.85f, size.height * 0.85f),
        )
    }
}

/** 把桌面那份 RemoteViews 挂到这个 FrameLayout 上。失败就把错误**画出来**，不许静默变白。 */
internal fun mountWidgetViews(
    host: FrameLayout,
    ctx: Context,
    info: WidgetStageInfo,
    spec: String,
    alpha: Float,
) {
    runCatching {
        val m = info.model
        val view = if (m != null) {
            TodayWidgetProvider.viewsSized(ctx, m, info.wPx, info.hPx, spec, alpha, preview = true).apply(ctx, host)
        } else {
            TodayWidgetProvider.themeViews(ctx, info.wPx, info.hPx, spec, alpha).apply(ctx, host)
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }.onFailure { err ->
        host.addView(android.widget.TextView(ctx).apply {
            text = "RemoteViews 渲染失败：${err.message}"
            setTextColor(0xFFFF8888.toInt())
            textSize = 11f
            setPadding(16, 16, 16, 16)
        })
    }
}

/**
 * 自检 = 卡的读数（不再自己画一张）。卡就在上面，这些数字描述的就是它。
 *
 * @param spec      当前生效的规格（含"试穿"）。
 * @param savedSpec 真正存在设置里的规格（用来标出"你正在试穿，没落盘"）。
 */
@Composable
fun WidgetDiagPanel(
    info: WidgetStageInfo,
    spec: String,
    savedSpec: String,
    onTrySpec: (String?) -> Unit,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(C.card, RoundedCornerShape(12.dp))
            .border(1.dp, C.line, RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("小组件自检", color = C.txt2, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "上面那张卡就是桌面那份 · ${info.deskW.roundToInt()}×${info.deskH.roundToInt()}dp",
            color = C.txt3, fontSize = 10.sp, modifier = Modifier.weight(1f),
        )
        Text(if (open) "⌃" else "⌄", color = C.txt3, fontSize = 13.sp)
    }

    if (!open) return

    Spacer(Modifier.height(8.dp))
    Text(
        "上面那张卡不是仿画的：它就是桌面收到的同一份 RemoteViews，按 launcher 报的格子尺寸画。" +
            "这里有图、桌面没有 ⇒ 桌面把它丢了，删掉小组件重加；这里也是黑的 ⇒ 我这边画错了，把这页截图发我。",
        color = C.txt3, fontSize = 10.sp, lineHeight = 15.sp,
    )
    Spacer(Modifier.height(8.dp))

    // 三颗试穿胶囊：换规格只改上面那张卡（不写回设置），一眼看出是哪一类画不出来。
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(
            null to "当前",
            "default" to "纯色",
            "img_aurora" to "随包图片",
            WidgetThemes.USER_IMAGE_SPEC to "我的图",
        ).forEach { (s, label) ->
            // 只有"当前"和"试穿中的那个"能高亮：用户现在的主题本身就是"我的图"时，
            // 两个都亮会让人以为点了两下（真机上确实出过）。
            val trying = spec != savedSpec
            val on = if (s == null) !trying else trying && spec == s
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) C.violet.copy(alpha = 0.22f) else C.card)
                    .border(1.dp, if (on) C.violet.copy(alpha = 0.6f) else C.line, RoundedCornerShape(20.dp))
                    .clickable { onTrySpec(s) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) { Text(label, color = if (on) C.violet else C.txt3, fontSize = 10.5.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 下面这些读数都按**同一张卡**的像素尺寸算，不再另开 900×400 的口径。
    val bmp = remember(spec, info.wPx, info.hPx) {
        runCatching {
            TodayWidgetProvider.backgroundBitmapFor(ctx, spec, info.wPx, info.hPx)
        }.getOrNull()
    }
    val frames = remember(spec, info.wPx, info.hPx) {
        runCatching {
            WidgetFrames.forSpec(ctx, spec, info.wPx, info.hPx)
        }.getOrNull()
    }
    val center = remember(bmp) {
        bmp?.let { Integer.toHexString(it.getPixel(it.width / 2, it.height / 2)) } ?: "-"
    }

    Text(
        WidgetThemes.userImageInfo(ctx) + " · 中心像素 #$center · " + brightnessOf(bmp),
        color = C.txt3, fontSize = 10.sp,
    )
    Text(
        if (frames != null) "走轮播：${frames.size} 帧 · ${frames.first().width}×${frames.first().height}"
        else "走静态底图：" + (bmp?.let { "${it.width}×${it.height} ≈ ${it.byteCount / 1024}KB" } ?: "无"),
        color = C.txt3, fontSize = 10.sp,
    )
    Text(
        if (info.instances.isEmpty()) "桌面上还没有这个小组件 —— 长按桌面 → 添加小组件 → 校园助手"
        else "桌面实例 " + info.instances.joinToString("，") { "#${it.id} ${it.wDp}×${it.hDp}dp" },
        color = if (info.instances.isEmpty()) C.amber else C.txt3, fontSize = 10.sp,
    )
    Text(
        "这张卡按 ${info.wPx}×${info.hPx}px 画（${info.deskW.roundToInt()}×${info.deskH.roundToInt()}dp × 密度）" +
            " · 放得下 ${info.rows} 行",
        color = C.txt3, fontSize = 10.sp,
    )
    if (spec != savedSpec) {
        Text(
            "正在试穿「$spec」，没有落盘；点「当前」回到设置里那张。",
            color = C.amber, fontSize = 10.sp,
        )
    }
}

/** 底图平均亮度（0~255）。纯黑底图 = "没有主题"，得让它自己说出来。 */
fun brightnessOf(bmp: android.graphics.Bitmap?): String {
    if (bmp == null) return "亮度 -"
    val s = android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true)
    var sum = 0L
    for (y in 0 until s.height) for (x in 0 until s.width) {
        val p = s.getPixel(x, y)
        sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
    val avg = (sum / (s.width * s.height)).toInt()
    return "平均亮度 $avg/255" + if (avg < 12) "（几乎全黑）" else ""
}
