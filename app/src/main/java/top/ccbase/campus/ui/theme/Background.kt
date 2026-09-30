package top.ccbase.campus.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.io.File
import java.io.FileOutputStream

/**
 * 内置背景预设 —— **程序生成的矢量渐变，不是图片资产**。
 *
 * 为什么不用照片：① 版权（本仓是 MIT，只覆盖代码，第三方照片进公开仓是版权问题）；
 * ② 体积（每张照片都进 APK，现在是 7.6 MB，几张就翻倍）。渐变在渲染时算出来，
 * 一个字节都不进包，换色板也不影响。
 *
 * 深色系为主：铺上去之后由 `SCRIM_ALPHA` 那层遮罩把它压到底色附近，
 * 保证文字仍可读（浅色主题下遮罩是近白底，同一张渐变会变成很淡的底，观感是干净的浅色）。
 */
data class BgPreset(val id: String, val name: String, val colors: List<Color>, val radial: Boolean = false)

val BgPresets: List<BgPreset> = listOf(
    BgPreset("dusk", "暮紫", listOf(Color(0xFF2A1B4E), Color(0xFF0B0910))),
    BgPreset("violet", "紫青", listOf(Color(0xFF3B1E5A), Color(0xFF0E2B3A)), radial = true),
    BgPreset("ocean", "深海", listOf(Color(0xFF0E2A3F), Color(0xFF07131C))),
    BgPreset("mint", "青雾", listOf(Color(0xFF123A38), Color(0xFF07171A))),
    BgPreset("sand", "暖砂", listOf(Color(0xFF3A2A1C), Color(0xFF171009))),
    BgPreset("graphite", "石墨", listOf(Color(0xFF1C1D22), Color(0xFF0A0A0C))),
)

fun presetById(id: String): BgPreset? = BgPresets.firstOrNull { it.id == id }

fun BgPreset.brush(): Brush =
    if (radial) Brush.radialGradient(colors) else Brush.linearGradient(colors)

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

    /** 长边上限：1440 在 1080p 屏上是 1.33 倍，放大也不糊，解码只要几 MB */
    private const val MAX_SIDE = 1440
    private const val QUALITY = 88

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE_NAME)

    /**
     * 把用户选中的那张图**降采样后**存进 `filesDir`。
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
