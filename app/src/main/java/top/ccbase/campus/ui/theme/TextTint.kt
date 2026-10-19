package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 文字颜色（2026-10-19 用户："要支持更改app内和小控件的文字颜色，
 * 把更改文字颜色的控件放在预览旁边"）。
 *
 * 一个入口同时管**两处**：整个 App 的正文色令牌（`C.txt/txt2/txt3`）和桌面小组件的
 * 标题/正文/次要文字。理由很实际 —— 他换的是"这套主题的字看着舒不舒服"，
 * 不是在挑哪个组件；要 App 白字、小组件淡紫字，那是两个审美互相打架。
 *
 * 实现上只动**文字**令牌，底色/卡片/强调色一律不碰：文字色一改，对比度是唯一
 * 需要守的线，所以 `ThemeTokenTest` 里加了一条"改色后正文对底仍要够亮"的守卫。
 */
object TextTint {

    const val AUTO = "auto"

    /** 顺序按"从最稳到最有味道"排：默认在最前，用户不选了也能一眼看到它在哪 */
    val options: List<Pair<String, String>> = listOf(
        AUTO to "默认",
        // 基本色先给齐（2026-10-19 用户："文字颜色要包含黑色等基本色"）：
        // 浅色主题下默认字是深色，但换成浅底图/深底图时他需要能手动定成黑或白。
        "#000000" to "黑",
        "#1F1F1F" to "深灰",
        "#FFFFFF" to "白",
        "#F4EAD8" to "暖白",
        "#D9C8FF" to "淡紫",
        "#BFE2FF" to "淡蓝",
        "#FFD2E1" to "淡粉",
        "#FFE3A3" to "暖黄",
        "#C8F3D6" to "淡绿",
    )

    /** 组合侧读它 → 选一个当场重组合 */
    val state = mutableStateOf(AUTO)

    fun parse(value: String): Color? = when (value) {
        AUTO -> null
        else -> runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
    }

    fun current(): Color? = parse(state.value)

    fun read(ctx: Context): String =
        AppearanceStore.prefs(ctx).getString("text_tint", AUTO) ?: AUTO

    fun set(ctx: Context, value: String) {
        AppearanceStore.prefs(ctx).edit().putString("text_tint", value).apply()
        state.value = value
    }

    // ---- 可读性守卫（2026-10-19 用户："浅色主题底下的标签颜色都看不见了"）----
    //
    // 用户自己挑文字颜色时会挑到"这套底色上根本没有对比"的那一支：
    // 浅色主题 + 白字 = 一片白；深色主题 + 黑字 = 一片黑。
    // 所以这里不禁止他选，但**落笔前过一道对比度闸**：
    //   选的颜色够看就用它 → 不够看就退回色板原本的字色 → 原本的也不够看，
    //   就按底色的明暗给纯黑/纯白。永远不会出现"选了字反而看不见"。

    private const val MIN_CONTRAST = 3.0f

    private fun lum(c: Color): Float = c.luminance()

    fun contrast(a: Color, b: Color): Float {
        val la = lum(a)
        val lb = lum(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** 保证 c 在 bg 上看得见；看不见就依次退到 base、纯黑/纯白 */
    private fun readable(c: Color, base: Color, bg: Color): Color {
        if (contrast(c, bg) >= MIN_CONTRAST) return c
        if (contrast(base, bg) >= MIN_CONTRAST) return base
        return if (lum(bg) > 0.5f) Color(0xFF111318) else Color(0xFFF7F7F9)
    }

    /** 三级文字的层次：主色原样，二级/三级往底色里退 */
    fun head(tint: Color?, base: Color, bg: Color): Color = readable(tint ?: base, base, bg)
    fun body(tint: Color?, base: Color, bg: Color): Color =
        if (tint == null) readable(base, base, bg) else readable(lerp(tint, bg, 0.18f), base, bg)

    fun dim(tint: Color?, base: Color, bg: Color): Color =
        if (tint == null) readable(base, base, bg) else readable(lerp(tint, bg, 0.45f), base, bg)

    // ---- 小组件那侧要的是 ARGB Int（RemoteViews.setTextColor）----

    fun currentArgb(): Int? = current()?.toArgb()

    /** level：0 标题 / 1 正文 / 2 次要 */
    fun apply(tint: Int?, base: Int, bg: Int, level: Int): Int {
        if (tint == null) return base
        val t = Color(tint)
        val b = Color(bg)
        val out = when (level) {
            0 -> t
            1 -> lerp(t, b, 0.18f)
            else -> lerp(t, b, 0.45f)
        }
        return readable(out, Color(base), b).toArgb()
    }
}
