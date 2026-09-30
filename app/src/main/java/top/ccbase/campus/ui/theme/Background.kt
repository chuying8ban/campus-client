package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 内置背景 —— **程序生成的矢量图层，不是图片资产**。
 *
 * 为什么不用照片：① 版权（本仓是 MIT，只覆盖代码，第三方照片进公开仓是版权问题）；
 * ② 体积（每张照片都进 APK，几张就翻倍）。图层在渲染时算出来，一个字节都不进包。
 *
 * ## 一款背景 = 一层竖向渐变 + 若干径向光晕
 *
 * 老版本是"两段深色 + 一张固定遮罩 0.82"，问题在**深色到深色本身就没有对比**，
 * 再压上 0.82 的遮罩只剩 18% 透出来 ⇒ 2026-09-30 用户的原话是"切换背景根本看不出区别"。
 * 实测：屏顶与纯色底的最大通道差只有 4~13/255，六款里最接近的两款只差 **2/255**。
 * 现在颜色自己带亮度（渐变 + 光晕），遮罩改成**按可读性预算反算**（见 [scrimFor]），
 * 同一套可读性保证下，可见度从 Δ4~13 提到 Δ17~100。
 *
 * ## 两组、共 12 款
 *
 * - [BgStyle.RICH] 浓郁：两处对角光晕，像壁纸；
 * - [BgStyle.CALM] 淡雅：整屏单色淡染 + 顶部渐晕。
 *
 * 同一色相在两组的 id 分别是 `dusk` / `dusk-soft` —— **旧的 6 个 id 全部保留**，
 * 老用户已经选过的背景不会因为这次改版失效。
 */
data class BgGlow(val color: Color, val cx: Float, val cy: Float, val r: Float, val strength: Float)

enum class BgStyle(val label: String) { RICH("浓郁"), CALM("淡雅") }

data class BgPreset(
    val id: String,
    val name: String,
    val style: BgStyle,
    val base: List<Color>,
    val glows: List<BgGlow>,
)

private fun g(hex: Long, cx: Float, cy: Float, r: Float, s: Float) =
    BgGlow(Color(0xFF000000L or hex), cx, cy, r, s)

private fun presetOf(id: String, name: String, style: BgStyle, base: List<Long>, glows: List<BgGlow>) =
    BgPreset(id, name, style, base.map { Color(0xFF000000L or it) }, glows)

val BgPresets: List<BgPreset> = listOf(
    presetOf("dusk", "暮紫", BgStyle.RICH, listOf(0x2A1E52, 0x0D0A16), listOf(g(0x7C4DFF, 0.12f, 0.04f, 1.00f, 0.62f), g(0xFF4D8D, 0.92f, 0.88f, 0.85f, 0.30f))),
    presetOf("violet", "靛蓝", BgStyle.RICH, listOf(0x151A44, 0x0A0B18), listOf(g(0x4F7CFF, 0.16f, 0.06f, 1.00f, 0.55f), g(0x8B5CF6, 0.88f, 0.86f, 0.90f, 0.28f))),
    presetOf("ocean", "深海", BgStyle.RICH, listOf(0x0A2848, 0x060C14), listOf(g(0x35D6E8, 0.85f, 0.12f, 0.95f, 0.52f), g(0x2A6BFF, 0.10f, 0.92f, 0.90f, 0.34f))),
    presetOf("mint", "青雾", BgStyle.RICH, listOf(0x07282A, 0x061210), listOf(g(0x3BE8A0, 0.18f, 0.10f, 0.95f, 0.50f), g(0x14B8A6, 0.88f, 0.84f, 0.90f, 0.30f))),
    presetOf("sand", "暖砂", BgStyle.RICH, listOf(0x3A2417, 0x150E08), listOf(g(0xFF9A3D, 0.14f, 0.08f, 0.95f, 0.55f), g(0xFF6B9A, 0.90f, 0.90f, 0.85f, 0.26f))),
    presetOf("graphite", "石墨", BgStyle.RICH, listOf(0x1B1C23, 0x0B0B0F), listOf(g(0x9FB4D8, 0.50f, -0.05f, 1.10f, 0.26f))),

    presetOf("dusk-soft", "雾紫", BgStyle.CALM, listOf(0x1B1538, 0x0C0A13), listOf(g(0x8B5CF6, 0.50f, -0.08f, 0.90f, 0.34f))),
    presetOf("violet-soft", "月蓝", BgStyle.CALM, listOf(0x101B3C, 0x090C16), listOf(g(0x4F7CFF, 0.50f, -0.08f, 0.90f, 0.32f))),
    presetOf("ocean-soft", "青瓷", BgStyle.CALM, listOf(0x07202F, 0x060C12), listOf(g(0x38BDF8, 0.50f, -0.08f, 0.90f, 0.26f))),
    presetOf("mint-soft", "苔青", BgStyle.CALM, listOf(0x08241B, 0x06110C), listOf(g(0x34D399, 0.50f, -0.08f, 0.90f, 0.28f))),
    presetOf("sand-soft", "麦沙", BgStyle.CALM, listOf(0x2A1808, 0x110B05), listOf(g(0xFF9A3D, 0.50f, -0.08f, 0.90f, 0.34f))),
    presetOf("graphite-soft", "素灰", BgStyle.CALM, listOf(0x1C1B1E, 0x0C0B0D), listOf(g(0xD8CFC2, 0.50f, -0.08f, 0.90f, 0.18f))),
)

fun presetById(id: String): BgPreset? = BgPresets.firstOrNull { it.id == id }

// ── 色彩数学（预算反算与 `ThemeTokenTest` 共用同一套，别再写第二份）────────

private fun lin(v: Float): Float =
    if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

internal fun relLum(c: Color): Float = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relLum(a)
    val lb = relLum(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** 把 [src] 以不透明度 [a] **叠在** [dst] 之上（sRGB 逐通道，与 Skia 的 source-over 一致） */
internal fun over(dst: Color, src: Color, a: Float): Color {
    val t = a.coerceIn(0f, 1f)
    return Color(
        red = src.red * t + dst.red * (1f - t),
        green = src.green * t + dst.green * (1f - t),
        blue = src.blue * t + dst.blue * (1f - t),
        alpha = 1f,
    )
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

// ── 浅色主题：同色相重新提亮 ────────────────────────────────────────────────

/**
 * 把一款预设适配到主题上。
 *
 * 深色主题用原样；浅色主题**保留色相、把明度提上去**变成淡彩 —— 直接拿深色渐变铺在浅底上，
 * 无论遮罩多大都会洗成中性灰（实测六款合成出来全是 `#D2D0D9` 那一族，浅色下同样
 * "看不出区别"）。提亮之后渐变自己就落在浅底能承受的带子里，色相还在。
 */
fun forTheme(p: BgPreset, dark: Boolean): BgPreset {
    if (dark) return p

    fun lift(c: Color, targetL: Float): Color {
        val mx = maxOf(c.red, c.green, c.blue)
        val mn = minOf(c.red, c.green, c.blue)
        val l = (mx + mn) / 2f
        val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                c.red -> ((c.green - c.blue) / d + if (c.green < c.blue) 6f else 0f) / 6f
                c.green -> ((c.blue - c.red) / d + 2f) / 6f
                else -> ((c.red - c.green) / d + 4f) / 6f
            }
        }
        val s = if (l <= 0f || l >= 1f) 0f else d / (1f - abs(2f * l - 1f))
        return hsl(h, min(1f, s * 0.85f), targetL)
    }

    return p.copy(
        base = listOf(lift(p.base.first(), 0.90f), lift(p.base.last(), 0.96f)),
        glows = p.glows.map { it.copy(color = lift(it.color, 0.86f), strength = it.strength * 0.55f) },
    )
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h * 6f) % 2f - 1f))
    val m = l - c / 2f
    val rgb = when ((h * 6f).toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(rgb.first + m, rgb.second + m, rgb.third + m, 1f)
}

// ── 采样与预算反算 ─────────────────────────────────────────────────────────

private const val GRID = 32

/**
 * 预设铺满屏、**还没叠遮罩**时某个位置的像素值。
 *
 * 必须与 `drawBgPreset` 里那三层一一对应（竖向渐变 `t = ty`；光晕按 smoothstep 衰减）。
 * 两边一旦不一致，"按预算反算"就变成按一个不存在的画面算 —— 这是这套设计唯一的脆弱点，
 * 所以 `BackgroundGateTest` 会拿它同时验**可读性**与**可辨识性**。
 */
internal fun presetPixel(p: BgPreset, tx: Float, ty: Float): Color {
    var c = lerpColor(p.base.first(), p.base.last(), ty.coerceIn(0f, 1f))
    p.glows.forEach { gl ->
        val dx = tx - gl.cx
        val dy = ty - gl.cy
        val d = sqrt(dx * dx + dy * dy)
        var w = (1f - d / gl.r).coerceIn(0f, 1f)
        w = w * w * (3f - 2f * w)                       // smoothstep，对应 Canvas 里那 5 个色标
        if (w > 0f) c = over(c, gl.color, gl.strength * w)
    }
    return c
}

/** 整个画面里**最亮**与**最暗**的那两个像素 */
internal fun extremesOf(p: BgPreset): Pair<Color, Color> {
    var hi = p.base.first()
    var lo = p.base.first()
    var hiL = -1f
    var loL = 2f
    for (j in 0 until GRID) {
        for (i in 0 until GRID) {
            val c = presetPixel(p, i.toFloat() / (GRID - 1), j.toFloat() / (GRID - 1))
            val l = relLum(c)
            if (l > hiL) { hiL = l; hi = c }
            if (l < loL) { loL = l; lo = c }
        }
    }
    return hi to lo
}

/** 文字在"合成底"上的最低对比度要求（与 `ThemeTokenTest` 同口径，按色板分开） */
internal fun textThresholds(p: CampusPalette): Triple<Float, Float, Float> =
    if (p.bg.luminance() > 0.5f) Triple(4.5f, 4.5f, 3.0f) else Triple(4.5f, 4.5f, 2.2f)

/**
 * 把「最亮/最暗两个极值像素」压到刚好看得清，需要多少遮罩 —— **能不加就不加**。
 *
 * 两个极值都要卡：深色主题文字是亮的，卡的是最亮像素；浅色主题文字是暗的，卡的是最暗像素
 * （只卡最亮会让浅色主题的渐变区完全没人管）。二分出**最小**的可读遮罩，再留 6% 余量。
 */
internal fun solveScrim(hi: Color, lo: Color, p: CampusPalette): Float {
    val (t1, t2, t3) = textThresholds(p)
    val texts = listOf(p.txt, p.txt2, p.txt3)
    val thrs = listOf(t1, t2, t3)

    fun ok(a: Float): Boolean = listOf(hi, lo).all { src ->
        val comp = over(src, p.bg, a)
        texts.indices.all { contrastRatio(texts[it], comp) >= thrs[it] }
    }

    var loA = 0f
    var hiA = 1f
    repeat(40) {
        val mid = (loA + hiA) / 2f
        if (ok(mid)) hiA = mid else loA = mid
    }
    var a = min(hiA * 1.06f + 0.02f, 0.96f)
    while (!ok(a) && a < 0.99f) a = min(a + 0.01f, 0.99f)
    return a
}

private val scrimCache = HashMap<String, Float>()

/** 这一款预设在当前色板下该用多少遮罩（采样不便宜，算一次就缓存住） */
fun scrimFor(p: BgPreset, palette: CampusPalette): Float {
    val key = "${p.id}|${if (palette.bg.luminance() > 0.5f) "L" else "D"}"
    return scrimCache.getOrPut(key) {
        val (hi, lo) = extremesOf(p)
        solveScrim(hi, lo, palette)
    }
}

/** 背景图：按**这张图自己的**明暗算遮罩 —— 暗图可以很轻（照片真看得见），亮图自动压回去 */
fun photoScrim(extremes: Pair<Color, Color>?, palette: CampusPalette): Float =
    if (extremes == null) SCRIM_ALPHA else solveScrim(extremes.first, extremes.second, palette)

/**
 * 一块"已经铺好背景"的画布 —— 外观页的色卡、照片缩略图、预览都用它。
 *
 * 与真正的背景层共用 `drawBgPreset`，所以色卡不是"代表色块"，而是这款背景的真实渲染
 * （连遮罩厚度都是按当前色板反算出来的那一份）。色卡和真机长得一样，选背景才有意义。
 */
@Composable
fun BgPresetSurface(preset: BgPreset, dark: Boolean, modifier: Modifier = Modifier) {
    val themed = remember(preset, dark) { forTheme(preset, dark) }
    val scrim = remember(themed, dark) { scrimFor(themed, palette) }
    Canvas(modifier) { drawBgPreset(themed, scrim, palette) }
}

// ── 用户自己选的背景图 ─────────────────────────────────────────────────────

/**
 * 用户自己选的背景图 —— **只在本机，不上传任何地方**。
 *
 * 三条硬约束（对应界面上那句承诺，`AppearanceGuardTest` 会扫源码守住）：
 *  1. **不申请任何存储权限**：选图走系统照片选择器（`PickVisualMedia`），
 *     App 只拿到用户挑中的那一个 URI 的读权限，拿不到相册；
 *  2. **只落 `filesDir`**（不是 cacheDir —— 那不是"我们的文件"，系统随时会清）；
 *  3. **不发网络请求**：这一整块代码里没有 `Net` / `CampusApi` 之类的引用。
 *
 * 为什么要降采样：现在手机随手一张就 12MP（4000×3000），原图解码 = 48 MB 位图，
 * 铺个背景足以把 App 撑到 OOM；长边压到 [MAX_SIDE] 之后是几百 KB 级别。
 */
object BackgroundPhoto {

    /** 固定文件名：换图即覆盖，不会攒下一堆旧图（也就不需要"清理孤儿文件"那套） */
    private const val FILE_NAME = "bg.jpg"
    private const val DIR = "appearance"

    /** 这张图的明暗极值（两行十六进制）。存极值而不是直接存遮罩：换主题要按新色板重算。 */
    private const val EXTREMES_NAME = "bg.extremes"

    /** 长边上限：1440 在 1080p 屏上是 1.33 倍，放大也不糊，解码只要几 MB */
    private const val MAX_SIDE = 1440
    private const val QUALITY = 88

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE_NAME)

    /** 存用户那张图的明暗极值。公开是为了让测试能直接构造"亮图/暗图"两种情形。 */
    fun extremesFile(ctx: Context): File = File(File(ctx.filesDir, DIR), EXTREMES_NAME)

    /**
     * 把用户选中的那张图**降采样后**存进 `filesDir`，并顺手量出它的明暗极值。
     *
     * @return 写入时刻（毫秒）；失败返回 null。时刻会被记进设置里当**缓存位移**用 ——
     *   文件名固定，界面靠它判断"这张图换了、得重新解码"，否则会一直显示旧图。
     */
    fun save(ctx: Context, uri: Uri): Long? = try {
        val bitmap = decodeDownsampled(ctx, uri) ?: return null
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
        writeExtremes(ctx, measure(bitmap))
        bitmap.recycle()
        if (f.exists()) f.delete()
        val ok = tmp.renameTo(f)
        if (!ok) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        System.currentTimeMillis()
    } catch (e: Exception) {
        android.util.Log.w("BackgroundPhoto", "背景图保存失败", e)
        null
    }

    /** 解码已经存好的那张图（文件本身已经是降采样过的，这里直接全解） */
    fun load(ctx: Context): Bitmap? = try {
        val f = file(ctx)
        if (f.isFile && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        runCatching { extremesFile(ctx).delete() }
    }

    /** 读回极值；文件不在（旧版本装的、或量失败）返回 null，由调用方退回 [SCRIM_ALPHA] */
    fun readExtremes(ctx: Context): Pair<Color, Color>? = runCatching {
        val f = extremesFile(ctx)
        if (!f.isFile) return null
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return null
        Color(lines[0].toLong(16).toInt()) to Color(lines[1].toLong(16).toInt())
    }.getOrNull()

    /**
     * 量这张图的明暗极值（每 4 个像素取一个，够用且很快）。
     *
     * 为什么要量：遮罩以前写死 0.82，等于**按最坏情况（纯白图）**给所有图都压这么厚，
     * 一张深色风景照也只能透出 18%，看着就是一片深灰。按图自己算，暗图几乎不用压。
     */
    private fun measure(b: Bitmap): Pair<Color, Color> {
        var hi = Color.Black
        var lo = Color.White
        var hiL = -1f
        var loL = 2f
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = Color(b.getPixel(x, y))
                val l = relLum(c)
                if (l > hiL) { hiL = l; hi = c }
                if (l < loL) { loL = l; lo = c }
                x += 4
            }
            y += 4
        }
        return hi to lo
    }

    private fun writeExtremes(ctx: Context, ex: Pair<Color, Color>) {
        runCatching {
            val f = extremesFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText("%08X\n%08X\n".format(ex.first.toArgb(), ex.second.toArgb()))
        }
    }

    /**
     * 两趟解码：第一趟只读尺寸算出 `inSampleSize`，第二趟才真正解码。
     * 一趟解 12MP 原图在小内存机器上就是 OOM，所以这里必须两趟。
     */
    private fun decodeDownsampled(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize 必须是 2 的幂，所以这里取"缩到长边 ≤ MAX_SIDE 的最小 2 的幂"。
        // 注意条件写法：曾写成 `长边 / (sample*2) >= MAX_SIDE` —— 2000 宽时 sample 停在 1，
        // 等于**根本没降采样**（原图直接解码就是 OOM 那条路）；`AppearanceStoreTest` 的那条
        // "长边 ≤ 1440" 断言把它抓出来了。代价：幂次步进，实际结果可能比上限再小一倍（1000 而非 1440），可以接受。
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
