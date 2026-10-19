package top.ccbase.campus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
    /** 底色：渐变底图从它画起（预置与 DIY 都用它） */
    val bgColor: Int = 0xF70B0910.toInt(),
    /** 浅色主题：描边/柔光的算法与深色相反（浅底要暗描边，不然看不见） */
    val light: Boolean = false,
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

    /**
     * 纯色预置。注意 `bgColor` 现在是**渐变底图的起点色**（见 WidgetArt）：
     * 2026-10-18 起不再用固定 drawable 当底 —— 一块死纯色在桌面上就是一条黑边，
     * 用户原话"太简陋了"。每个预置的底色取自原来那批 drawable，观感不变、质感上去。
     */
    val presets: List<WidgetTheme> = listOf(
        WidgetTheme("default", "默认深紫", bgColor = 0xF70B0910.toInt(), accentColor = 0xFFA06BFF.toInt()),
        WidgetTheme("black", "纯黑", bgColor = 0xF2000000.toInt(), accentColor = 0xFFA06BFF.toInt()),
        WidgetTheme("navy", "深蓝", bgColor = 0xF70E1626.toInt(), accentColor = 0xFF4FE3D0.toInt()),
        WidgetTheme("plum", "梅紫", bgColor = 0xF71A1024.toInt(), accentColor = 0xFFC08BFF.toInt()),
        WidgetTheme("forest", "墨绿", bgColor = 0xF70C1A14.toInt(), accentColor = 0xFF57D98A.toInt()),
        WidgetTheme("sand", "暖沙", bgColor = 0xF71F1913.toInt(), accentColor = 0xFFFFB454.toInt()),
        WidgetTheme(
            "light", "浅色", bgColor = 0xF7F7F6FB.toInt(), light = true,
            headColor = 0xFF1A1626.toInt(), bodyColor = 0xFF4A4460.toInt(),
            dimColor = 0xFF6E6788.toInt(), accentColor = 0xFF6D28D9.toInt(),
        ),
        WidgetTheme("glass", "半透明", bgColor = 0x660B0910, accentColor = 0xFFA06BFF.toInt()),
    )

    /** 随包的图片主题（图在 res/drawable-nodpi/）。 */
    val imageThemes: List<WidgetTheme> = listOf(
        WidgetTheme("img_aurora", "海边", imageRes = R.drawable.widget_art_aurora,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF8AE9D6.toInt()),
        WidgetTheme("img_dune", "红叶", imageRes = R.drawable.widget_art_dune,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFFFFC98A.toInt()),
        WidgetTheme("img_night", "夕照", imageRes = R.drawable.widget_art_night,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF9BB8FF.toInt()),
        WidgetTheme("img_mint", "神社", imageRes = R.drawable.widget_art_mint,
            headColor = IMG_HEAD, bodyColor = IMG_BODY, dimColor = IMG_DIM, accentColor = 0xFF9BE7C4.toInt()),
    )

    /** 规格：预置 id / `img_*` / `diy:<hue>:<dark>` / `user`（自己上传的那张）。 */
    fun spec(ctx: Context): String = prefs(ctx).getString(K_THEME, DEFAULT) ?: DEFAULT

    fun set(ctx: Context, spec: String) {
        prefs(ctx).edit().putString(K_THEME, spec).apply()
    }

    /**
     * 选主题的唯一正确入口（2026-10-19 用户："更换的主题并没有立刻出现，过了一会才出现，
     * 你要自动刷新主题状态"）。
     *
     * 以前只写 pref 就走人：桌面要等下一次 `refresh()` 的第二次渲染（先渲染一版旧的、
     * 再预热新帧、再渲染一版），所以"过了一会才出现"。这里把三件事绑成一个动作：
     *   ① 清帧缓存（旧主题的帧绝不能拿来当新主题的首帧）；
     *   ② 清底图缓存；
     *   ③ 立刻让 provider 重画。
     */
    fun applyNow(ctx: Context, spec: String) {
        set(ctx, spec)
        WidgetFrames.clear()
        TodayWidgetProvider.refresh(ctx)
    }

    fun hasUserImage(ctx: Context): Boolean = userImageFile(ctx).exists()

    /**
     * 应用小组件主题，**先把关再落地**：图片类规格必须在当前机器上真的能解码，
     * 否则不写设置（否则桌面会变成一张没有任何图的黑卡，用户以为"换了没反应"）。
     *
     * @return null = 已应用；非 null = 不能应用，内容是给用户看的一句话
     */
    private const val K_ALPHA = "alpha"

    /**
     * 小组件主题的不透明度（2026-10-19 用户："希望能够调整小组件主题的透明度"）。
     *
     * 存的是"能看到多少"，1.0 = 原来的样子，0.35 = 桌面壁纸能透上来。
     * 不是给 ImageView 设 alpha（RemoteViews 里 `setImageAlpha` 能不能过 binder 得看 ROM），
     * 而是在**生成底图时就把透明度画进去** —— 走到桌面的一定是我们算好的像素。
     */
    fun alpha(ctx: Context): Float =
        prefs(ctx).getFloat(K_ALPHA, 1f).coerceIn(0.35f, 1f)

    fun setAlpha(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(K_ALPHA, v.coerceIn(0.35f, 1f)).apply()
    }

    private const val K_SPEED = "play_speed"

    /**
     * 小组件动态主题的播放速度（2026-10-19 用户："小控件要支持更改播放速度"）。
     *
     * 0.5 = 慢、1 = 正常、2 = 快。桌面不会自己翻帧（RemoteViews 没有动画），
     * 帧是按"墙钟时间"挑的 —— 这里改的就是那根时间轴走多快，
     * 所以慢速和快速都真的动，只是推进速度不同。
     */
    fun speed(ctx: Context): Float =
        prefs(ctx).getFloat(K_SPEED, 1f).coerceIn(0.35f, 3f)

    fun setSpeed(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(K_SPEED, v.coerceIn(0.35f, 3f)).apply()
    }

    fun apply(ctx: Context, spec: String): String? {
        val theme = of(spec)
        if (theme.hasImage && imageBitmap(ctx, theme) == null) {
            return "这张图读不出来，换一张再试"
        }
        set(ctx, spec)
        return null
    }

    /** 给「小组件自检」用的一句话诊断：文件在不在、多大、能不能解码。 */
    fun userImageInfo(ctx: Context): String {
        val f = userImageFile(ctx)
        if (!f.isFile) return "我的图：文件不存在"
        if (f.length() == 0L) return "我的图：文件是空的"
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0) return "我的图：${f.length() / 1024}KB，解不出来"
        return "我的图：${f.length() / 1024}KB ${opts.outWidth}×${opts.outHeight}"
    }

    fun userImageFile(ctx: Context): File = File(ctx.filesDir, USER_FILE)

    /**
     * 保存用户选的图：**先等比缩到 1200px 内再存**。
     * 理由有两个：原图动辄 8~12MB 会拖慢每次刷新；RemoteViews 传 bitmap 还有 1MB 的 binder 上限。
     */
    /**
     * 太暗的图先提亮再落盘。
     *
     * 2026-10-19 用户："使用图片主题也只显示为黑色"。他那张"我的图"实测平均亮度 20/255
     * （中心像素 #010c29）—— 是从一段暗色动图里抽的帧。这种图铺到小组件上，跟黑色没区别。
     * 这里按亮度自动抬一档（不超过 3 倍），让它至少能看出画面。
     */
    fun saveUserImage(ctx: Context, src: Bitmap): Boolean {
        val bmp = ImgLift.lift(src)
        // 落了盘还要**回读验一遍**：compress 失败时文件是 0 字节，但 write 本身不报错。
        // 2026-10-19 的坑正是这个 —— 文件"存在"、规格也设成 `user`，解码却是 null，
        // 卡片就退成深色渐变，用户看到的是"换了主题没反应 / 一片黑"。
        // 验不过就降一档再存；还不行就老实返回 false，让界面提示，而不是留个半成品。
        for ((maxW, quality) in listOf(1200f to 88, 800f to 80, 600f to 75)) {
            val ok = runCatching {
                val scale = (maxW / bmp.width).coerceAtMost(1f)
                val out = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                } else bmp
                val f = userImageFile(ctx)
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                f.length() > 1024 && opts.outWidth > 0
            }.getOrDefault(false)
            if (ok) return true
        }
        runCatching { userImageFile(ctx).delete() }
        return false
    }

    /** 「随时间」：同一个 id，每次刷新按当前钟点算一套颜色（桌面上看起来一整天在变） */
    const val DYNAMIC_SPEC = "dyn"

    /**
     * 按钟点给一套颜色。桌面上能做到的"动态"只有这一种：
     * RemoteViews 不能跑动画（launcher 不会去 start 那个 drawable），但小组件本来每 30 分钟
     * 就会重画一次 —— 那就让每次重画的颜色跟着窗外光线走。
     */
    fun dynamic(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): WidgetTheme = when {
        hour in 5..7 -> WidgetTheme(
            DYNAMIC_SPEC, "清晨", bgColor = 0xF70A0E1C.toInt(), accentColor = 0xFF9DB6FF.toInt(),
        )
        hour in 8..10 -> WidgetTheme(
            DYNAMIC_SPEC, "上午", bgColor = 0xF70A1218.toInt(), accentColor = 0xFF6FE3D2.toInt(),
        )
        hour in 11..13 -> WidgetTheme(
            DYNAMIC_SPEC, "正午", bgColor = 0xF70B1016.toInt(), accentColor = 0xFF7FC8FF.toInt(),
        )
        hour in 14..16 -> WidgetTheme(
            DYNAMIC_SPEC, "午后", bgColor = 0xF7141010.toInt(), accentColor = 0xFFFFB86B.toInt(),
        )
        hour in 17..18 -> WidgetTheme(
            DYNAMIC_SPEC, "黄昏", bgColor = 0xF7180E16.toInt(), accentColor = 0xFFFF8E6B.toInt(),
        )
        hour in 19..22 -> WidgetTheme(
            DYNAMIC_SPEC, "夜", bgColor = 0xF70B0913.toInt(), accentColor = 0xFFA06BFF.toInt(),
        )
        else -> WidgetTheme(
            DYNAMIC_SPEC, "深夜", bgColor = 0xF7050509.toInt(), accentColor = 0xFF7C6BE8.toInt(),
        )
    }

    /** 规格 → 主题。认不出来就退回默认（外观设置出错不许把桌面卡片搞坏）。 */
    fun of(spec: String): WidgetTheme {
        if (spec == DYNAMIC_SPEC) return dynamic()
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
                id = "diy", name = "自定义", bgColor = (hsv(h, 0.10f, 0.97f) or 0xF7000000.toInt()), light = true,
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
                // 用户那张图（filesDir/widget_theme.jpg）。文件可能被清理工具删掉、也可能当初压缩失败，
                // 这时**别直接退回深色渐变**（用户看到的就是"主题换了没反应、卡片一片黑"）：
                // 依次退到小组件槽 / App 槽里存的原图，只要有一份能解码就用它。
                val f = userImageFile(ctx)
                (if (f.isFile && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null)
                    ?: top.ccbase.campus.ui.theme.UserMedia.stillBitmap(
                        ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.WIDGET,
                    )
                    ?: top.ccbase.campus.ui.theme.UserMedia.stillBitmap(
                        ctx, top.ccbase.campus.ui.theme.UserMedia.Slot.APP,
                    )
            } else {
                BitmapFactory.decodeResource(ctx.resources, theme.imageRes)
            } ?: return null
            // 静态图这条路原来漏了提亮：用户那张"我的图"平均亮度只有 39/255，
            // 再压一层 40%~60% 黑遮罩就是纯黑 —— 而"微动"走的是帧分支（3.4.18 已提亮），
            // 所以出现了"静止不显示、点了动效就有了"的怪现象。
            val lifted = ImgLift.lift(src)
            val scale = (targetPx.toFloat() / lifted.width).coerceAtMost(1f)
            if (scale >= 1f) lifted
            else Bitmap.createScaledBitmap(lifted, (lifted.width * scale).toInt(), (lifted.height * scale).toInt(), true)
        }.getOrNull()
    }

    private fun hsv(h: Float, s: Float, v: Float): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
