package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import top.ccbase.campus.R

/**
 * 小组件自己的外观 —— 和 App 内主题**分开存**。
 *
 * 为什么分开：App 里换主题是为了"这个 App 看着顺眼"，桌面那张卡片要考虑的是
 * **和壁纸搭不搭**，两件事没有必然关系。
 *
 * 三类外观（2026-10-18 按用户要求补齐「图片主题」，参考小米背屏那套做法）：
 *  ① 纯色/描边预置：颜色写死在一张张 `res/drawable/widget_bg_*.xml` 里 ——
 *    RemoteViews 不能应用任意 drawable，只有预置能拿到底图 + 圆角的完整效果；
 *  ② 图片预置：随包带的几张图（`drawable-nodpi/widget_art_*`），选一层暗色蒙版压住，
 *    保证白字在任何图上都读得清；
 *  ③ 自己上传：用户从相册选一张，存进 `filesDir/widget_theme.jpg`。**不联网、不上传**。
 *
 * DIY 纯色（`diy:<hue>:<dark 0|1>`）走运行时上色：Android 12+ 由系统裁圆角，老系统直角。
 */
data class WidgetTheme(
    val id: String,
    val name: String,
    /** 圆角底图资源；0 表示"运行时装颜色"（DIY 用） */
    val bgRes: Int = 0,
    /** DIY 时的底色 */
    val bgColor: Int = 0xF70B0910.toInt(),
    val headColor: Int = 0xFFEAE7F4.toInt(),
    val bodyColor: Int = 0xFFA49DBD.toInt(),
    val dimColor: Int = 0xFF6F6889.toInt(),
    val accentColor: Int = 0xFFA06BFF.toInt(),
    /** 随包图片主题的 drawable；0 表示不用图 */
    val imageRes: Int = 0,
    /** 用户自己上传的那张（filesDir/widget_theme.jpg） */
    val userImage: Boolean = false,
) {
    val hasImage: Boolean get() = imageRes != 0 || userImage
}

object WidgetThemes {

    const val DEFAULT = "default"
    const val USER_IMAGE_SPEC = "user"
    private const val FILE = "campus_widget"
    private const val K_THEME = "theme"
    private const val USER_FILE = "widget_theme.jpg"

    // 图片主题的通用字色：图上有暗蒙版，所以文字固定用偏亮的一套，不跟图片主色走
    private const val IMG_HEAD = 0xFFF4F1FA.toInt()
    private const val IMG_BODY = 0xFFD9D3E8.toInt()
    private const val IMG_DIM = 0xFFB5AECB.toInt()

    val presets: List<WidgetTheme> = listOf(
        WidgetTheme("default", "默认深紫", bgRes = R.drawable.widget_bg, accentColor = 0xFFA06BFF.toInt()),
        WidgetTheme("black", "纯黑", bgRes = R.drawable.widget_bg_black, accentColor = 0xFFA06BFF.toInt()),
        WidgetTheme("navy", "深蓝", bgRes = R.drawable.widget_bg_navy, accentColor = 0xFF4FE3D0.toInt()),
        WidgetTheme("plum", "梅紫", bgRes = R.drawable.widget_bg_plum, accentColor = 0xFFA06BFF.toInt()),
        WidgetTheme("forest", "墨绿", bgRes = R.drawable.widget_bg_forest, accentColor = 0xFF57D98A.toInt()),
        WidgetTheme("sand", "暖沙", bgRes = R.drawable.widget_bg_sand, accentColor = 0xFFFFB454.toInt()),
        WidgetTheme(
            "light", "浅色", bgRes = R.drawable.widget_bg_light,
            headColor = 0xFF1A1626.toInt(), bodyColor = 0xFF4A4460.toInt(),
            dimColor = 0xFF6E6788.toInt(), accentColor = 0xFF6D28D9.toInt(),
        ),
        WidgetTheme("glass", "半透明", bgRes = R.drawable.widget_bg_glass, accentColor = 0xFFA06BFF.toInt()),
    )

    /** 随包的图片主题（图在 res/drawable-nodpi/）。 */
    val imageThemes: List<WidgetTheme> = listOf(
        WidgetTheme("img_aurora", "极光", imageRes = R.drawable.widget_art_aurora,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF8AE9D6.toInt()),
        WidgetTheme("img_dune", "沙丘", imageRes = R.drawable.widget_art_dune,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFFFFC98A.toInt()),
        WidgetTheme("img_night", "夜街", imageRes = R.drawable.widget_art_night,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF9BB8FF.toInt()),
        WidgetTheme("img_mint", "青雾", imageRes = R.drawable.widget_art_mint,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF9BE7C4.toInt()),
    )

    /** 规格：预置 id / `img_*` / `diy:<hue>:<dark>` / `user`（自己上传的那张）。 */
    fun spec(ctx: Context): String = prefs(ctx).getString(K_THEME, DEFAULT) ?: DEFAULT

    fun set(ctx: Context, spec: String) {
        prefs(ctx).edit().putString(K_THEME, spec).apply()
    }

    fun hasUserImage(ctx: Context): Boolean = userImageFile(ctx).exists()

    fun userImageFile(ctx: Context): File = File(ctx.filesDir, USER_FILE)

    /**
     * 保存用户选的图：**先等比缩到 1200px 内再存**。
     * 理由有两个：原图动辄 8~12MB 会拖慢每次刷新；RemoteViews 传 bitmap 还有 1MB 的 binder 上限。
     */
    fun saveUserImage(ctx: Context, bmp: Bitmap): Boolean = runCatching {
        val maxW = 1200f
        val scale = (maxW / bmp.width).coerceAtMost(1f)
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        userImageFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    }.getOrDefault(false)

    /** 规格 → 主题。认不出来就退回默认（外观设置出错不许把桌面卡片搞坏）。 */
    fun of(spec: String): WidgetTheme {
        if (spec == USER_IMAGE_SPEC) {
            return WidgetTheme(
                "user", "自己的图片", userImage = true,
                headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM,
            )
        }
        if (spec.startsWith("diy:")) {
            val hue = spec.removePrefix("diy:").substringBefore(":").toFloatOrNull() ?: 0f
            val dark = spec.substringAfterLast(":", "1") == "1"
            return diy(hue, dark)
        }
        return presets.firstOrNull { it.id == spec }
            ?: imageThemes.firstOrNull { it.id == spec }
            ?: presets.first()
    }

    /**
     * 自己调：只给一个色相，底色/强调色按固定明度算出来。
     * 明度写死是为了**文字永远看得清** —— 让用户连着亮度一起调，只会调出一张看不清的卡片。
     */
    fun diy(hue: Float, dark: Boolean = true): WidgetTheme {
        val h = ((hue % 360f) + 360f) % 360f
        return if (dark) {
            WidgetTheme(
                id = "diy", name = "自定义", bgColor = (hsv(h, 0.30f, 0.13f) or 0xF7000000.toInt()),
                headColor = 0xFFEAE7F4.toInt(), bodyColor = 0xFFA49DBD.toInt(),
                dimColor = 0xFF6F6889.toInt(), accentColor = hsv(h, 0.55f, 0.96f),
            )
        } else {
            WidgetTheme(
                id = "diy", name = "自定义", bgColor = (hsv(h, 0.10f, 0.97f) or 0xF7000000.toInt()),
                headColor = 0xFF1A1626.toInt(), bodyColor = 0xFF4A4460.toInt(),
                dimColor = 0xFF6E6788.toInt(), accentColor = hsv(h, 0.62f, 0.55f),
            )
        }
    }

    /**
     * 读出图片底图（预设或用户上传），并缩到小组件该用的尺寸。
     * 返回 null = 这次没有图（走纯色分支）。
     */
    fun imageBitmap(ctx: Context, theme: WidgetTheme, targetPx: Int = 720): Bitmap? {
        if (!theme.hasImage) return null
        return runCatching {
            val src = if (theme.userImage) {
                val f = userImageFile(ctx)
                if (!f.exists()) return null
                BitmapFactory.decodeFile(f.absolutePath)
            } else {
                BitmapFactory.decodeResource(ctx.resources, theme.imageRes)
            } ?: return null
            val scale = (targetPx.toFloat() / src.width).coerceAtMost(1f)
            if (scale >= 1f) src
            else Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        }.getOrNull()
    }

    private fun hsv(h: Float, s: Float, v: Float): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
