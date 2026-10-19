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

    /** App 内的字色（组合侧读它 → 选一个当场重组合） */
    val state = mutableStateOf(AUTO)

    /**
     * 桌面小组件的字色 —— **和 App 分开存、分开生效**。
     *
     * 2026-10-19 用户："文字颜色要只改自己的那部分，app主题那里的只改app的，
     * 小控件那里的只改小控件的"。上一版一个 pref 管两处：他在 App 主题页挑个黑色，
     * 桌面上的小组件也跟着变（反过来也是），两个页面互相打架。
     * 现在两个 key、两个状态：App 页只写 `text_tint`，小组件页只写 `widget_text_tint`。
     */
    val widgetState = mutableStateOf(AUTO)

    private const val K_APP = "text_tint"
    private const val K_WIDGET = "widget_text_tint"

    /**
     * 自己解 #RRGGBB —— 不用 `android.graphics.Color.parseColor`。
     * 那个是 Android 桩，在纯 JVM 单测里会抛 "not mocked"，返回 null，
     * 于是"黑字深底该压白幕"这类判断在测试里静默走错分支（本地实测踩过）。
     */
    fun parse(value: String): Color? {
        if (value == AUTO) return null
        val h = value.trim().removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        val n = h.toLongOrNull(16) ?: return null
        val argb = if (h.length == 6) (0xFF000000L or n) else n
        return Color(argb.toInt())
    }

    fun current(): Color? = parse(state.value)

    fun read(ctx: Context): String =
        AppearanceStore.prefs(ctx).getString(K_APP, AUTO) ?: AUTO

    fun readWidget(ctx: Context): String =
        AppearanceStore.prefs(ctx).getString(K_WIDGET, AUTO) ?: AUTO

    /** 只改 App 内的字色：不碰桌面 */
    fun set(ctx: Context, value: String) {
        AppearanceStore.prefs(ctx).edit().putString(K_APP, value).apply()
        state.value = value
    }

    /** 只改桌面小组件的字色：不碰 App */
    fun setWidget(ctx: Context, value: String) {
        AppearanceStore.prefs(ctx).edit().putString(K_WIDGET, value).apply()
        widgetState.value = value
        runCatching { top.ccbase.campus.widget.TodayWidgetProvider.refresh(ctx) }
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

    /**
     * 三级文字的层次：主色原样，二级/三级往底色里退。
     *
     * 2026-10-19 修正（用户："为什么我改成黑色里面的文字还是白色"）：
     * 上一版在这里做了**静默回退** —— 黑字在深色底板上对比不够就偷偷改回白色，
     * 于是"选了黑还是白"。**用户的选择必须赢**：这里原样返回他挑的颜色，
     * 可读性改由[scrimArgb]在背景层补（深字配浅幕、浅字配深幕）。
     */
    fun head(tint: Color?, base: Color, bg: Color): Color = tint ?: base
    fun body(tint: Color?, base: Color, bg: Color): Color =
        if (tint == null) base else lerp(tint, bg, 0.18f)

    fun dim(tint: Color?, base: Color, bg: Color): Color =
        if (tint == null) base else lerp(tint, bg, 0.45f)

    /** 用户挑的是一支深色的字？ */
    fun isDarkTint(value: String): Boolean {
        val c = parse(value) ?: return false
        return lum(c) < 0.45f
    }

    // ---- 小组件那侧要的是 ARGB Int（RemoteViews.setTextColor）----

    fun currentArgb(): Int? = current()?.toArgb()

    /** 小组件那一路的 ARGB（App 用什么颜色与它无关） */
    fun currentWidgetArgb(): Int? = parse(widgetState.value)?.toArgb()

    /**
     * 背景幕：用户挑了深色字 → 背景要压白；挑了浅色字 → 背景要压暗。
     *
     * 不透明度取 0.34：够把照片/动图的明暗拉回中间，又不至于把画面洗成灰板。
     * 没挑（AUTO）时返回 0，背景保持原样 —— 默认色板本来就是配好的。
     */
    fun scrimArgb(paletteBgArgb: Int): Int = scrimArgbFor(state.value, paletteBgArgb)

    /** 纯函数版本（测试用）：给定挑的颜色和底板，算出该压什么幕 */
    fun scrimArgbFor(tint: String, paletteBgArgb: Int): Int {
        if (tint == AUTO) return 0
        val dark = isDarkTint(tint)
        val bgLum = lum(Color(paletteBgArgb))
        // 字和底板同向时最糟（深底黑字 / 浅底白字）→ 幕要更厚
        val tooClose = if (dark) bgLum < 0.5f else bgLum > 0.5f
        val a = if (tooClose) 0x8C else 0x57            // 55% / 34%
        return (a shl 24) or if (dark) 0xFFFFFF else 0x000000
    }

    /** level：0 标题 / 1 正文 / 2 次要 */
    fun apply(tint: Int?, base: Int, bg: Int, level: Int): Int {
        if (tint == null) return base
        val t = Color(tint)
        val b = Color(bg)
        // 同样按用户挑的颜色走（小组件那侧靠 WidgetArt 的遮罩保证可读）
        return when (level) {
            0 -> t
            1 -> lerp(t, b, 0.18f)
            else -> lerp(t, b, 0.45f)
        }.toArgb()
    }
}
