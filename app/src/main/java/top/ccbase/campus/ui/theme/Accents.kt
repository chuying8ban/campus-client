package top.ccbase.campus.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 强调色（主题）系统 —— 「大量主题 + 自己调 + 跟随壁纸」三件事共用一条管线。
 *
 * **不改底色，只转色相**：底/字/半透明令牌是网页版逐条对过的（见 Theme.kt 顶部那段），
 * 深浅两套的对比度由 `ThemeTokenTest` 按算的守着。随便换底色就会把"三级灰字看得见"
 * 这类事撞坏。所以这里的做法是：拿住已经验过的那套色板，把**语义色（紫/青/品红/琥珀/红/绿）**
 * 整体转一个色相 —— 转色相不改明度与饱和度，对比度几乎不动，但整屏的气质全变。
 * 底色只做一点点同色偏向（dark 6%、light 4%），够看出"这套主题是暖的还是冷的"，
 * 又不足以把文字的对比度拖下去。
 */

data class AccentPreset(val id: String, val name: String, val hue: Float, val tint: Float = 1f)

/**
 * 12 套预置。第一套是**原样**（颜色与网页版逐字一致），其余靠色相偏移。
 * 挑色相的时候尽量让"主色"落在舒服的位置，而不是机械地每 30° 一个。
 */
val ACCENT_PRESETS: List<AccentPreset> = listOf(
    AccentPreset("default", "默认紫", 0f),
    AccentPreset("fog", "雾蓝", 30f),
    AccentPreset("ocean", "深海", 70f),
    AccentPreset("teal", "松石", 110f),
    AccentPreset("mint", "薄荷", 145f),
    AccentPreset("leaf", "青叶", 175f),
    AccentPreset("amber", "琥珀", 205f),
    AccentPreset("sunset", "落日", 235f),
    AccentPreset("rose", "玫瑰", 265f),
    AccentPreset("cherry", "樱粉", 290f),
    AccentPreset("indigo", "靛青", 320f),
    AccentPreset("graphite", "石墨", 350f),
)

fun accentPreset(id: String): AccentPreset? = ACCENT_PRESETS.firstOrNull { it.id == id }

/** 主题规格的字符串形式，存在 SharedPreferences 里（`AppearanceStore` 负责读写）。 */
object AccentSpec {
    const val DEFAULT = "default"

    fun preset(id: String) = "preset:$id"
    fun diy(hue: Float, tint: Float) = "diy:${hue.toInt()}:${tint.coerceIn(0f, 1f)}"

    fun isDiy(spec: String): Boolean = spec.startsWith("diy:")
    fun diyHue(spec: String): Float? = spec.removePrefix("diy:").substringBefore(":").toFloatOrNull()
    fun diyTint(spec: String): Float =
        spec.removePrefix("diy:").substringAfter(":", "").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    fun presetId(spec: String): String? =
        if (spec.startsWith("preset:")) spec.removePrefix("preset:") else null
}

/**
 * 色相旋转（HSV 里只动 H）。
 *
 * 用 `android.graphics.Color` 而不是自己写 RGB↔HSV：那套是系统实现，跟 WallpaperManager、
 * 系统取色器算出来的结果一致，不会出现"选同一个色号，两处颜色不一样"。
 */
fun rotateHue(color: Color, degrees: Float): Color {
    if (degrees == 0f) return color
    val base = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), base)
    val hue = (base[0] + degrees + 360f) % 360f
    val alpha = (color.alpha * 255f).toInt()
    val target = color.luminance()

    // 只转色相是**不够**的：同样 S/V 下，蓝紫很亮、绿红很暗。直接换色相会把
    // 浅色底板上的强调字直接压到看不清（这一点是 `AccentsTest` 用对比度算出来的，
    // 不是拍脑袋）。所以转完再**把明度追回原值**：固定色相，二分 V。
    var sat = base[1]
    repeat(10) {
        var lo = 0f
        var hi = 1f
        repeat(16) {
            val mid = (lo + hi) / 2f
            val c = Color(android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, sat, mid)))
            if (c.luminance() < target) lo = mid else hi = mid
        }
        val v = (lo + hi) / 2f
        val c = Color(android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, sat, v)))
        if (kotlin.math.abs(c.luminance() - target) < 0.004f) return c
        // 这个色相在饱和度这么高时追不到目标亮度（浅底上的亮色常见）→ 降饱和再来
        sat *= 0.85f
    }
    return Color(android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, sat, 1f)))
}

/** 底色往某个色相偏一点点（不是换色，只是让整屏看得出冷暖）。 */
fun tintedBg(bg: Color, hue: Float, amount: Float, dark: Boolean): Color {
    if (amount <= 0f || hue == 0f) return bg
    val target = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue % 360f, 0.35f, if (dark) 0.22f else 0.97f)))
    val k = (if (dark) 0.06f else 0.04f) * amount.coerceIn(0f, 1f)
    return Color(
        red = bg.red + (target.red - bg.red) * k,
        green = bg.green + (target.green - bg.green) * k,
        blue = bg.blue + (target.blue - bg.blue) * k,
        alpha = bg.alpha,
    )
}

/**
 * 把"主题规格"落成一套色板。
 * 规格里出现不认识的 id（老版本存下的、或手工写坏）→ 退回默认，**绝不抛异常**
 * —— 外观设置崩了会让整个 App 起不来。
 */
fun paletteWithAccent(base: CampusPalette, spec: String): CampusPalette {
    val dark = base.bg.luminance() <= 0.5f
    val (hue, tint) = when {
        spec == AccentSpec.DEFAULT || spec.isBlank() -> return base
        AccentSpec.isDiy(spec) -> (AccentSpec.diyHue(spec) ?: return base) to AccentSpec.diyTint(spec)
        AccentSpec.presetId(spec) != null -> {
            val p = accentPreset(AccentSpec.presetId(spec)!!) ?: return base
            p.hue to p.tint
        }
        else -> return base
    }
    return base.copy(
        bg = tintedBg(base.bg, hue, tint, dark),
        bgSoft = tintedBg(base.bgSoft, hue, tint, dark),
        violet = rotateHue(base.violet, hue),
        magenta = rotateHue(base.magenta, hue),
        cyan = rotateHue(base.cyan, hue),
        amber = rotateHue(base.amber, hue),
        red = rotateHue(base.red, hue),
        green = rotateHue(base.green, hue),
    )
}
