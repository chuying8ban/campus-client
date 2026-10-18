package top.ccbase.campus.widget

import android.content.Context
import top.ccbase.campus.R

/**
 * 小组件自己的配色 —— 和 App 内的主题**分开存**。
 *
 * 为什么分开：App 里换主题是为了"这个 App 看着顺眼"，桌面那张卡片要考虑的是
 * **和壁纸搭不搭**，两件事没有必然关系。硬绑在一起，用户想把 App 用紫色、
 * 桌面用黑色就做不到。
 *
 * 预置都带**圆角底图**（`res/drawable/widget_bg_*.xml`），颜色写死在那几张 XML 里 ——
 * RemoteViews 不能应用任意 drawable，只有预置能拿到底图 + 描边的完整效果。
 * 自己调色（DIY）走运行时上色（`setBackgroundColor`），在 Android 12+ 上系统会按
 * 自己的圆角裁切，观感是一致的；老系统则是直角，这一点在设置页里直说。
 */
data class WidgetTheme(
    val id: String,
    val name: String,
    /** 底图资源；0 表示"运行时装颜色"（DIY 用） */
    val bgRes: Int = 0,
    /** DIY 时的底色 */
    val bgColor: Int = 0xF70B0910.toInt(),
    val headColor: Int = 0xFFEAE7F4.toInt(),
    val bodyColor: Int = 0xFFA49DBD.toInt(),
    val dimColor: Int = 0xFF6F6889.toInt(),
    val accentColor: Int = 0xFFA06BFF.toInt(),
)

object WidgetThemes {

    const val DEFAULT = "default"
    private const val FILE = "campus_widget"
    private const val K_THEME = "theme"

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

    /** 规格：预置 id，或 `diy:<hue>:<dark 0|1>`。 */
    fun spec(ctx: Context): String = prefs(ctx).getString(K_THEME, DEFAULT) ?: DEFAULT

    fun set(ctx: Context, spec: String) {
        prefs(ctx).edit().putString(K_THEME, spec).apply()
    }

    /** 规格 → 主题。认不出来就退回默认（外观设置出错不许把桌面卡片搞坏）。 */
    fun of(spec: String): WidgetTheme {
        if (spec.startsWith("diy:")) {
            val hue = spec.removePrefix("diy:").substringBefore(":").toFloatOrNull() ?: 0f
            val light = spec.substringAfterLast(":", "1") == "0"
            return diy(hue, light)
        }
        return presets.firstOrNull { it.id == spec } ?: presets.first()
    }

    /**
     * 自己调：只给一个色相，底色/强调色按固定明度算出来。
     * 明度写死是为了**文字永远看得清** —— 让用户连着亮度一起调，只会调出一张看不清的卡片。
     */
    fun diy(hue: Float, light: Boolean = false): WidgetTheme {
        val h = ((hue % 360f) + 360f) % 360f
        return if (light) {
            val bg = hsv(h, 0.10f, 0.97f)
            WidgetTheme(
                id = "diy", name = "自定义", bgColor = (bg or 0xF7000000.toInt()),
                headColor = 0xFF1A1626.toInt(), bodyColor = 0xFF4A4460.toInt(),
                dimColor = 0xFF6E6788.toInt(), accentColor = hsv(h, 0.62f, 0.55f),
            )
        } else {
            val bg = hsv(h, 0.30f, 0.13f)
            WidgetTheme(
                id = "diy", name = "自定义", bgColor = (bg or 0xF7000000.toInt()),
                headColor = 0xFFEAE7F4.toInt(), bodyColor = 0xFFA49DBD.toInt(),
                dimColor = 0xFF6F6889.toInt(), accentColor = hsv(h, 0.55f, 0.96f),
            )
        }
    }

    private fun hsv(h: Float, s: Float, v: Float): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
