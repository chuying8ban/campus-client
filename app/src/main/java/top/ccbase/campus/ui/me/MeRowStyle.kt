package top.ccbase.campus.ui.me

import androidx.compose.ui.graphics.Color

/**
 * 「我的」页里每一行文字的**唯一色源**。
 *
 * 为什么单独抽出来：2026-10-20 用户截图指出「自动后台更新」那一段的字色和上下行不一样。
 * 根因不是"忘了改"，而是**同一个层级散落着三四份写法**——
 * 普通行用的是 `onBackground@0.88` + 副标题 `@0.4`，而自动更新那一段标题没写 color
 * （吃 Material 默认的 `LocalContentColor` = `onSurface`，在彩色主题下和 onBackground 不是一个色），
 * 副标题又写成 `@0.55`。两处偏差叠在一起就成了"这块字看着发虚"。
 *
 * 现在所有行都从这里取色：层级只有三档（标题 / 副标题 / 禁用），改一次全页一致。
 */
internal object MeRowStyle {

    /** 标题（可点项的正文）透明度 */
    const val TITLE_ALPHA = 0.88f

    /** 副标题 / 说明文字透明度 */
    const val HINT_ALPHA = 0.4f

    /** 禁用状态的标题透明度（灰下去但仍能读出来"这里有个东西"） */
    const val DISABLED_ALPHA = 0.3f

    /** 危险项（删除、清空）标题透明度 */
    const val DANGER_ALPHA = 0.9f

    /**
     * 行标题色。
     *
     * @param danger 危险操作（红字）
     * @param enabled false 时压到 [DISABLED_ALPHA]，不参与点击
     */
    fun titleColor(
        onBackground: Color,
        error: Color,
        danger: Boolean = false,
        enabled: Boolean = true,
    ): Color = when {
        danger -> error.copy(alpha = if (enabled) DANGER_ALPHA else 0.35f)
        else -> onBackground.copy(alpha = if (enabled) TITLE_ALPHA else DISABLED_ALPHA)
    }

    /** 段落正文（较长的解释性文字）透明度。比标题淡一档，但仍要能长时间读。 */
    const val BODY_ALPHA = 0.62f

    /** 分割线 / 边框这类非文字的低透明度线条。 */
    const val LINE_ALPHA = 0.1f

    /** 副标题 / 说明文字色，和标题同源，只降透明度。 */
    fun hintColor(onBackground: Color): Color = onBackground.copy(alpha = HINT_ALPHA)

    /** 段落正文色。 */
    fun bodyColor(onBackground: Color): Color = onBackground.copy(alpha = BODY_ALPHA)

    /** 灰到"知道有这回事但点不了"的程度。 */
    fun disabledColor(onBackground: Color): Color = onBackground.copy(alpha = DISABLED_ALPHA)

    /** 分割线色。 */
    fun dividerColor(onBackground: Color): Color = onBackground.copy(alpha = LINE_ALPHA)

    /** 副标题的行高（中文小字太挤会显脏），11sp 配 17sp。 */
    const val HINT_LINE_SP = 17f
}
