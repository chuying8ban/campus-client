package top.ccbase.campus.ui.theme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一套色板 = 界面上所有颜色的单一真相源。
 *
 * 深色那套的色值**逐条从网页版 `static/style.css` 的 `:root` 搬过来**：
 * 原生版和网页版长期并存（网页版是兜底通道），两套配色只要有一点偏差，
 * 一眼就看得出"这不是同一个 App"。所以深色的真相源仍是那份 CSS，改配色两边一起改。
 *
 * 浅色那套是本 App 自己设计的（网页版目前没有浅色主题）：它**不是深色的反色**，
 * 而是重新挑过的一组值 —— 半透明令牌在浅底上必须换成**黑**（用白在近白底上等于看不见），
 * 语义色要压暗到在浅底上仍有对比度。这些约束不靠肉眼，由 `ThemeTokenTest` 逐条算对比度守住。
 *
 * ⚠️ 加/改 token 时同一件事要做三遍：`CampusPalette` 字段、两套色板、`ThemeTokenTest` 的阈值。
 */
data class CampusPalette(
    // 底
    val bg: Color,
    val bgSoft: Color,
    val card: Color,
    val cardHi: Color,
    val line: Color,
    val lineHi: Color,
    // 字（三级层次）
    val txt: Color,
    val txt2: Color,
    val txt3: Color,
    // 语义色
    val violet: Color,
    val magenta: Color,
    val cyan: Color,
    val amber: Color,
    val red: Color,
    val green: Color,
)

/** 深色（默认）—— 与网页版 `static/style.css` 的 `:root` 逐字一致，别在这里"顺手调一下"。 */
val DarkPalette = CampusPalette(
    bg = Color(0xFF0B0910),          // --bg
    bgSoft = Color(0xFF12101C),      // --bg-soft
    card = Color(0x0AFFFFFF),        // --card       rgba(255,255,255,.038)
    cardHi = Color(0x0FFFFFFF),      // --card-hi    rgba(255,255,255,.058)
    line = Color(0x13FFFFFF),        // --line       rgba(255,255,255,.075)
    lineHi = Color(0x24FFFFFF),      // --line-hi    rgba(255,255,255,.14)
    txt = Color(0xFFEAE7F4),         // --txt
    txt2 = Color(0xFFA49DBD),        // --txt-2
    txt3 = Color(0xFF6F6889),        // --txt-3
    violet = Color(0xFFA06BFF),      // 主色
    magenta = Color(0xFFFF5FA2),
    cyan = Color(0xFF4FE3D0),
    amber = Color(0xFFFFB454),       // 可解的问题（如时间冲突）
    red = Color(0xFFFF6B6B),         // 硬错
    green = Color(0xFF57D98A),       // 完成
)

/**
 * 浅色 —— 自己设计的一套，规矩两条：
 * ① 半透明底/线**换成黑**（深色里是"加一点白"，浅色里就是"加一点黑"）；
 * ② `txt2/txt3` 与每个语义色都必须**在 `bg` 上够对比度**，阈值见 `ThemeTokenTest`
 *    （txt/txt2/语义色 ≥ 4.5、txt3 ≥ 3.0）—— 用算的，不用眼睛看。
 * 本套实测：txt 16.5 / txt2 8.5 / txt3 4.9 / violet 6.6 / magenta 5.5 / cyan 5.1 /
 * amber 4.7 / red 5.2 / green 4.7。
 */
val LightPalette = CampusPalette(
    bg = Color(0xFFF7F6FB),
    bgSoft = Color(0xFFEFEDF7),
    card = Color(0x0A000000),
    cardHi = Color(0x0F000000),
    line = Color(0x13000000),
    lineHi = Color(0x24000000),
    txt = Color(0xFF1A1626),
    txt2 = Color(0xFF4A4460),
    txt3 = Color(0xFF6E6788),
    violet = Color(0xFF6D28D9),
    magenta = Color(0xFFC2185B),
    cyan = Color(0xFF0F766E),
    amber = Color(0xFFA85B00),
    red = Color(0xFFC62828),
    green = Color(0xFF15803D),
)

/**
 * 背景图之上那层遮罩的不透明度 —— **背景图那张图与文字之间垫的就是它**。
 *
 * 为什么要垫：`card/line` 本来就是半透明的（靠 alpha 表达"比底再亮一点"），
 * 直接铺照片/渐变会把文字糊进去（2026-09-17 那两次"看不清"的同类问题）。
 *
 * ⚠️ 这个常数现在**只当兜底**：内置 12 款背景按自己的可读性预算反算（`Background.kt` 的
 * `scrimFor`），用户自己的照片按**那张图自己的明暗**反算（`photoScrim`）—— 实际铺上去的遮罩
 * 几乎都不是 0.82，深色照片能低到 0.1 上下（照片真看得见）。只有"量不出图的明暗极值"
 * （旧版本装过、或量失败）时才退回这个按最坏情况算的值。
 *
 * 为什么是 0.82 —— 按**最坏情况**（纯白图 / 纯黑图）算出来的，不是拍的。等效底色 = `图*(1-SCRIM_ALPHA) + bg*SCRIM_ALPHA`；
 * 两套色板的"最坏方向"相反，所以两张表都要看（合成值由 @researcher 独立复算过，两位小数一致）：
 *
 * 深色（最坏＝**纯白图**，把底提亮）：
 *
 * | scrim | 等效底色 | txt | txt2 | txt3 |
 * |---|---|---|---|---|
 * | 0.72 | `#4F4E53` | 6.77 | **3.19 ✗** | 1.58 |
 * | **0.82** | `#37353B` | **9.93** | **4.69** | 2.32 |
 * | 0.90 | `#232228` | 12.95 | 6.11 | **3.02** |
 *
 * 浅色（最坏＝**纯黑图**，把底压黑）：
 *
 * | scrim | 等效底色 | txt | txt2 | txt3 |
 * |---|---|---|---|---|
 * | 0.72 | `#B2B1B5` | 8.30 | **4.31 ✗** | 2.49 |
 * | **0.82** | `#CBCACE` | **10.85** | **5.63** | **3.25** |
 *
 * ⇒ 同一个 0.82 在**两套色板**下都站得住（0.72 两边都有破线项），这就是它被定下来的依据。
 *
 * 剩下的折中只在**深色的三级灰字**上：它叠白图只能到 2.32 —— 要它也到 3.0 得把 scrim 提到 0.90，
 * 那样背景图只剩 10% 可见。**这条折中是明写的**：深色图片模式下三级文字会变弱，承载它的面板要用
 * `cardHi`；浅色那边没有这个问题（3.25 ≥ 3.0）。所以 `ThemeTokenTest` 的图片模式阈值是
 * **按色板分别**给的（深色 txt3 ≥ 2.2、浅色 ≥ 3.0），不是一刀切。
 *
 * 改这个值请让那条测试先过（它是按最坏情况验算的）。
 */
const val SCRIM_ALPHA = 0.82f

/**
 * 当前色板 —— 换主题靠**改这个快照状态**，而 `C` 的字段都是 `get()` 读它，
 * 于是任何读过 `C.txt` 这类令牌的组合函数会自动订阅、当场重组合。
 *
 * 为什么这么绕：全项目有 699 处 `C.x` 调用点，改成 CompositionLocal 要动 20 多个文件；
 * 只要保证 `C` 的字段"每次都现读"，那些调用点一行都不用改。
 */
private val paletteState = mutableStateOf(DarkPalette)

internal fun applyPalette(p: CampusPalette) {
    paletteState.value = p
}

/** 当前生效的色板（组合函数里读它才能跟着主题走）。 */
val palette: CampusPalette get() = paletteState.value

/**
 * 设计令牌。
 *
 * ⚠️ 每个字段都是 `get()`：**读的是当前色板**。别把它们存进文件级的 `val`/`const`
 * ——那会在类初始化时定死成深色，换主题后不跟着变。
 */
object C {
    // 底（深底不留纯黑）
    val bg: Color get() = paletteState.value.bg
    val bgSoft: Color get() = paletteState.value.bgSoft
    val card: Color get() = paletteState.value.card
    val cardHi: Color get() = paletteState.value.cardHi
    val line: Color get() = paletteState.value.line
    val lineHi: Color get() = paletteState.value.lineHi

    // 字（三级层次）
    //
    // 2026-10-19：用户可以自己挑文字颜色（`TextTint`），所以三级字不再直接回色板值，
    // 而是"色板值 → 用他挑的那支颜色按层级退到底色里"。没挑（AUTO）就是原样。
    private fun tinted(base: Color, level: Int): Color {
        val t = TextTint.current() ?: return base
        val bg = paletteState.value.bg
        return when (level) {
            0 -> TextTint.head(t, base, bg)
            1 -> TextTint.body(t, base, bg)
            else -> TextTint.dim(t, base, bg)
        }
    }

    val txt: Color get() = tinted(paletteState.value.txt, 0)
    val txt2: Color get() = tinted(paletteState.value.txt2, 1)
    val txt3: Color get() = tinted(paletteState.value.txt3, 2)

    // 语义色（状态档位：绿 / 琥珀 / 红）
    val violet: Color get() = paletteState.value.violet
    val magenta: Color get() = paletteState.value.magenta
    val cyan: Color get() = paletteState.value.cyan
    val amber: Color get() = paletteState.value.amber
    val red: Color get() = paletteState.value.red
    val green: Color get() = paletteState.value.green
}

/** 色板 → Material3 配色。浅底用 lightColorScheme、深底用 darkColorScheme（影响默认海拔叠加等）。 */
private fun schemeFor(p: CampusPalette) = if (p.bg.luminance() > 0.5f) {
    lightColorScheme(
        primary = p.violet,
        onPrimary = p.bg,
        secondary = p.cyan,
        onSecondary = p.bg,
        tertiary = p.magenta,
        background = p.bg,
        onBackground = p.txt,
        surface = p.bgSoft,
        onSurface = p.txt,
        surfaceVariant = p.bgSoft,
        onSurfaceVariant = p.txt2,
        outline = p.line,
        error = p.red,
        onError = p.bg,
    )
} else {
    darkColorScheme(
        primary = p.violet,
        onPrimary = p.bg,
        secondary = p.cyan,
        onSecondary = p.bg,
        tertiary = p.magenta,
        background = p.bg,
        onBackground = p.txt,
        surface = p.bgSoft,
        onSurface = p.txt,
        surfaceVariant = p.bgSoft,
        onSurfaceVariant = p.txt2,
        outline = p.line,
        error = p.red,
        onError = p.bg,
    )
}

/**
 * 数字一律等宽 —— 百分比、周次、余额、时间并排时不跳字。
 * 网页版也是这条规矩（`font-variant-numeric: tabular-nums`）。
 */
val Digits: TextStyle = TextStyle(fontFamily = FontFamily.Monospace)

private val CampusType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
    )
}

/**
 * 把用户选的字体铺到**所有**排版样式上（2026-10-19 用户："新增一个更改字体功能"）。
 *
 * 只改 App：小组件走它自己的 layout 变体（见 `WidgetFont`），两边各存各的 pref。
 * 注意 `Digits`（时间/数字）**不受影响** —— 它必须等宽，否则并排的数字会跳。
 */
private fun Typography.withFamily(opt: FontOpt): Typography {
    val f = opt.compose
    // 字重也一起铺：只换族名的话，"粗体/特粗/窄粗"这些选项在 App 里点了毫无反应
    // （2026-10-19 真机走查：字体胶囊长得一模一样，选了看不出变化）。
    fun androidx.compose.ui.text.TextStyle.w(): androidx.compose.ui.text.TextStyle =
        if (opt.weight > 0) copy(fontFamily = f, fontWeight = androidx.compose.ui.text.font.FontWeight(opt.weight))
        else copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.w(),
        displayMedium = displayMedium.w(),
        displaySmall = displaySmall.w(),
        headlineLarge = headlineLarge.w(),
        headlineMedium = headlineMedium.w(),
        headlineSmall = headlineSmall.w(),
        titleLarge = titleLarge.w(),
        titleMedium = titleMedium.w(),
        titleSmall = titleSmall.w(),
        bodyLarge = bodyLarge.w(),
        bodyMedium = bodyMedium.w(),
        bodySmall = bodySmall.w(),
        labelLarge = labelLarge.w(),
        labelMedium = labelMedium.w(),
        labelSmall = labelSmall.w(),
    )
}

private val CampusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),   // --r: 16px
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 解析当前该用哪套色板 —— 主题档（深色/浅色/跟随系统）**只在这里解析一次**：
 * `CampusTheme` 与宿主 Activity 的系统栏都调它，免得两处各写一份判断而走偏。
 *
 * 首次组合时把存下来的档位读进快照状态；`LaunchedEffect` 保证读过一次后就跟着设置变。
 */
@Composable
fun resolvePalette(): CampusPalette {
    // 外观档位在进程启动时已经读过一次（`CampusApplication.onCreate` → `AppearanceStore.bind`），
    // 这里只读内存快照：改档位靠 `AppearanceStore.theme` 这个状态触发重组合，不再每次组合都碰磁盘。
    val base = when (AppearanceStore.theme.value) {
        AppearanceStore.LIGHT -> LightPalette
        AppearanceStore.SYSTEM -> if (isSystemInDarkTheme()) DarkPalette else LightPalette
        else -> DarkPalette
    }
    // 主题（强调色）叠在底板上：只转色相 / 微调底色，不改那两套验过对比度的底板
    return paletteWithAccent(base, AppearanceStore.accent.value)
}

@Composable
fun CampusTheme(content: @Composable () -> Unit) {
    val p = resolvePalette()
    // 组合阶段不许写状态，放 SideEffect 里才能"换档位的那一帧"就生效
    SideEffect { applyPalette(p) }
    val bg = resolveBackground()
    Box(Modifier.fillMaxSize()) {
        // 背景层在最底下（纯色时这一层什么都不画，行为与没有背景图时一致）
        BackgroundLayer(bg, p)
        // 文字幕：**只在背景是图片/动图时才压**。
        //
        // 2026-10-19 用户："我点击文字颜色，为什么改的是背景颜色" —— 上一版在纯色主题上也压幕：
        // 挑个黑字，整页被刷成一层白（深底黑字=最糟情况 → 55% 白幕），看起来就是"背景被改了"。
        // 纯色主题的底板是自己配好的，字看不看得清由他自己选；只有照片/动图这类
        // **底板内容不受我们控制**的时候，才需要这一层保证下限。
        val hasImage = bg.startsWith(AppearanceStore.BG_PHOTO) ||
            bg.startsWith(ANIMPHOTO_PREFIX) ||
            bg.startsWith(ANIM_PREFIX) ||
            bg.startsWith(top.ccbase.campus.ui.theme.UserMedia.PREFIX)
        // 2026-10-19 用户："还有更改文字颜色时小部件的背景颜色不要一起改，取消那个遮罩功能"。
        // 遮罩就是"改字色把背景也改了"的唯一来源（字色越浅 / 越黑，算出来的幕越浓）。
        // 彻底关掉：字色只影响文字，背景永远只由主题决定。
        val scrim = 0
        if (scrim != 0) {
            Box(Modifier.fillMaxSize().background(Color(scrim)))
        }
        val appFont = AppFont.state.value
        val type = androidx.compose.runtime.remember(appFont) { CampusType.withFamily(appFont) }
    MaterialTheme(
            colorScheme = schemeFor(p),
            typography = type,
            shapes = CampusShapes,
            content = content,
        )
    }
}

/**
 * 背景这一层。顺序 = 渐变/图 → 遮罩 → 内容。
 *
 * 遮罩不是装饰：`card/line` 本身是半透明的，不垫一层的话铺上照片/亮渐变会把文字糊掉
 * （2026-09-17 那两次"看不清"的同类问题）。遮罩的颜色是**当前色板的 bg**。
 *
 * 但**遮罩的厚度不是常数**了：旧版对所有背景一律压 [SCRIM_ALPHA]，把渐变本身压到只剩
 * 18% 透出来，于是"切换背景看不出区别"（用户 2026-09-30 的原话，实测六款里最接近的两款
 * 只差 2/255）。现在内置预设按它自己的可读性预算反算（[scrimFor]）、背景图按那张图自己的
 * 明暗反算（[photoScrim]），[SCRIM_ALPHA] 只留给"量不出极值"的兜底。
 */
@Composable
internal fun BackgroundLayer(bg: String, p: CampusPalette) {
    // 动态主题先拦一道：它是"自己会动的画面"，不走预设/照片那两套静态逻辑
    if (bg.startsWith(ANIM_PREFIX)) {
        // spec 里带着色：同一动效可以配不同色系，光看 kind 不够
        val (kind, argb) = parseAnim(bg) ?: (DynKind.AURORA to p.violet.toArgb())
        val k = AppearanceStore.bgAlpha.value
        // 先用底色垫一层，动效按"浓淡"半透明盖上去 —— 动态主题也要能调浓淡
        Box(Modifier.fillMaxSize().background(p.bg))
        Box(Modifier.fillMaxSize().alpha(k)) {
            DynamicBackground(kind, Color(argb), Modifier.fillMaxSize())
        }
        // 浓淡做**双层**（用户 2026-10-18："动图的画面浓淡调整不太明显"）：
        // 只改图层 alpha 时，动效本来就偏暗、底色也暗，50% 和 100% 几乎看不出来。
        // 再叠一层 (1-k) 的底色，两条一起变 —— 同样一档差距肉眼立刻能分辨。
        // 底色覆盖别太狠：0.75 会让本来就不亮的动效彻底变黑（用户："用了之后主题就变成纯黑色"）。
        // 0.45 仍然能明显区分浓淡，但画面留着颜色。
        Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = (1f - k) * 0.45f)))
        // 淡了以后字更容易读，但顶栏/底部导航还是要一条压暗带（和照片同一套形状）
        // 浅色底同理（2026-10-20 实测）：这套薄遮罩配上深色动效 = 深底深字，整页看不见。
        // 动效拿不到明暗极值，只能按"全黑画面"兜底。
        val (mid, edge) = mediaScrim(light = p.bg.luminance() > 0.5f, k = k, edgeBias = 0.18f)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to p.bg.copy(alpha = edge),
                    0.18f to p.bg.copy(alpha = edge * 0.70f),
                    0.45f to p.bg.copy(alpha = mid),
                    0.75f to p.bg.copy(alpha = mid),
                    0.92f to p.bg.copy(alpha = edge * 0.78f),
                    1.00f to p.bg.copy(alpha = edge),
                ),
            ),
        )
        return
    }
    when {
        bg.startsWith(BG_PRESET_PREFIX) -> {
            val preset = presetById(bg.removePrefix(BG_PRESET_PREFIX))
            if (preset != null) {
                val themed = forTheme(preset, dark = p.bg.luminance() <= 0.5f)
                val scrim = scrimFor(themed, p)
                Canvas(Modifier.fillMaxSize()) { drawBgPreset(themed, scrim, p) }
            }
        }
        bg.startsWith(UserMedia.PREFIX) || bg.startsWith("mat:") -> {
            // 用户自己传的 gif / 视频，或者本地素材库里选中的那一件：真播，不假装
            val ctx = LocalContext.current
            val media = remember(bg) {
                MaterialLibrary.resolve(ctx, bg) ?: UserMedia.current(ctx)
            }
            Box(Modifier.fillMaxSize().background(p.bg))
            if (media != null) {
                // 50% 浓淡时只给 0.5 的图层 alpha，暗色片段等于再砍一半亮度 → 抬到 0.55~1.0
                val layer = (0.55f + 0.45f * AppearanceStore.bgAlpha.value).coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().alpha(layer)) {
                    UserMediaBackground(media, Modifier.fillMaxSize())
                }
                // 同上：视频/gif 也是"深色画面 + 深色底色"，单靠 alpha 调不出差别。
                // 2026-10-19：这里原来 0.75、压暗带到 0.72 —— 暗色片段直接被压成纯黑
                // （用户截图："主题选了一个动图后就这样了"，整屏黑）。和动效那边统一降下来。
                val k = AppearanceStore.bgAlpha.value
                Box(Modifier.fillMaxSize().background(p.bg.copy(alpha = (1f - k) * 0.45f)))
                // 视频/gif 拿不到"明暗极值"，遮罩只能按**最坏情况**兜底。
                // 深色底：原来的配方已经很轻（用户明确要求"要看得见动图"，压厚了就是一块灰板），
                //          只在浓淡上加一点点保险，别动它。
                // 浅色底：2026-10-20 实测踩到 —— 同一套薄遮罩 + 一张深色动图 = **深底配深字**，
                //          外观页的主题色名、"桌面小组件"标签几乎全看不见（截图 evidence-3.4.68/11b）。
                //          浅色模式里最坏情况是"全黑画面"，必须用近乎实心的白幕才把字托出来；
                //          留 45%/20% 让动图还看得见（用户要的是"图在、字也在"）。
                val (mid, edge) = mediaScrim(light = p.bg.luminance() > 0.5f, k = k, edgeBias = 0.16f)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.00f to p.bg.copy(alpha = edge),
                            0.18f to p.bg.copy(alpha = edge * 0.70f),
                            0.45f to p.bg.copy(alpha = mid),
                            0.75f to p.bg.copy(alpha = mid),
                            0.92f to p.bg.copy(alpha = edge * 0.78f),
                            1.00f to p.bg.copy(alpha = edge),
                        ),
                    ),
                )
            }
        }
        bg.startsWith(AppearanceStore.BG_PHOTO) || bg.startsWith(ANIMPHOTO_PREFIX) -> {
            // 图是异步解码的（IO 线程），解出来之前先铺底色，避免闪一下白
            val bmp = rememberBackgroundPhoto(bg)
            val ex = rememberPhotoExtremes(bg)
            val scrim = remember(ex, p.bg) { photoScrim(ex, p) }
            Box(Modifier.fillMaxSize().background(p.bg))
            val motion = parseAnimPhoto(bg)
            if (bmp != null && motion != null) {
                // 动态图片：微推近 + 前景粒子（动态壁纸那种），不透明度同样受"图片浓淡"管
                Box(Modifier.fillMaxSize().alpha(AppearanceStore.bgAlpha.value)) {
                    DynamicPhotoBackground(bmp, motion, p.violet, Modifier.fillMaxSize())
                }
            } else if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(AppearanceStore.bgAlpha.value),
                    contentScale = ContentScale.Crop,
                )
            }
            // 遮罩不再整片糊一层：整片均匀压暗会让"背景图"看起来像一块灰玻璃（用户：
            // "我希望 app 背景图能够更明显一些"）。改成上下两头厚、中间薄 —— 顶栏/底部导航
            // 那些字有把握，画面中间尽量露出原图。
            if (scrim > 0f) {
                // 中间那层最小值从 0.10 抬到 0.20（2026-10-19 用户："浅色主题底下的标签
                // 颜色都看不见了"）：0.10 时对比照片里偏亮的一块，字就糊进去了。
                // 上下两头仍然更厚，保证标题栏/导航栏的字。
                val mid = (scrim * 0.62f).coerceAtLeast(0.20f)
                val edge = (mid + 0.30f).coerceAtMost(0.94f)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.00f to p.bg.copy(alpha = edge),
                            0.16f to p.bg.copy(alpha = edge * 0.72f),
                            0.42f to p.bg.copy(alpha = mid),
                            0.72f to p.bg.copy(alpha = mid),
                            0.90f to p.bg.copy(alpha = edge * 0.78f),
                            1.00f to p.bg.copy(alpha = edge),
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * 动图 / 动效壁纸的遮罩梯度（中间档、上下两头的档）。
 *
 * 这两类背景**量不出明暗极值**（视频要播才知道画面、动效是代码画的），
 * 所以只能按最坏情况兜底 —— 这是它和 [photoScrim]、[scrimFor] 的根本区别。
 *
 * 深色底：最坏情况是"亮画面 + 亮字"，但用户 2026-10-19 明确要求动图要看得见
 * （压厚了就是一块灰板），所以只加很薄的一层，靠浓淡 [k] 微调。
 *
 * 浅色底：2026-10-20 实测踩到 —— 同一套薄遮罩 + 深色动漫动图 = **深底配深字**，
 * 外观页的主题色名、"桌面小组件"标签几乎全看不见（截图 `evidence-3.4.68/11b`）。
 * 浅色模式的最坏情况是"全黑画面"，必须用近乎实心的白幕才把字托出来，
 * 留 45% / 20% 让图还看得见（用户要的是"图在、字也在"）。
 */
internal fun mediaScrim(light: Boolean, k: Float, edgeBias: Float): Pair<Float, Float> {
    val mid = if (light) LIGHT_MEDIA_MID else (0.03f + 0.10f * k).coerceAtMost(0.16f)
    val edge = if (light) LIGHT_MEDIA_EDGE else (mid + edgeBias).coerceAtMost(0.46f)
    return mid to edge
}

/**
 * 浅色模式下动图/动效壁纸的白幕厚度：中间 0.82、两头 0.90。
 *
 * 这两个数是**算出来的**，不是拍的：浅色模式最坏情况是"全黑画面"，要让二级字到 4.5、
 * 三级灰字到 3.0，白幕至少要 0.80（0.55 时二级字只有 2.58、三级只有 1.49 —— 就是实测
 * 那张截图里"标签几乎看不见"的样子）。取 0.82 与 [SCRIM_ALPHA] 同口径。
 *
 * 代价说清楚：浅色模式 + 深色动图，图只剩两成——**这是"字看得清"的价钱**。
 * 想看满是画面的效果，用深色模式（那边遮罩很薄，用户 2026-10-19 明确要求过）。
 */
internal const val LIGHT_MEDIA_MID = 0.82f
internal const val LIGHT_MEDIA_EDGE = 0.90f

/**
 * 把一款预设画出来：**一层竖向渐变 + 若干径向光晕 + 一层按预算反算的遮罩**。
 *
 * 光晕那 5 个色标就是 smoothstep（1 → 0.84 → 0.5 → 0.16 → 0），与 [presetPixel] 的采样
 * 模型一一对应；**改这里必须同步改那里**，否则"按预算反算"就是按一个不存在的画面在算。
 *
 * 抽成 `DrawScope` 扩展，是为了让外观页的色卡、预览和真正的背景层用的是**同一段**绘制代码
 * —— 用户在外观页看到什么，退出去就是什么。
 */
internal fun DrawScope.drawBgPreset(preset: BgPreset, scrim: Float, p: CampusPalette) {
    drawRect(Brush.verticalGradient(preset.base))
    preset.glows.forEach { gl ->
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to gl.color.copy(alpha = gl.strength),
                    0.25f to gl.color.copy(alpha = gl.strength * 0.84f),
                    0.5f to gl.color.copy(alpha = gl.strength * 0.5f),
                    0.75f to gl.color.copy(alpha = gl.strength * 0.16f),
                    1f to gl.color.copy(alpha = 0f),
                ),
                center = Offset(size.width * gl.cx, size.height * gl.cy),
                radius = size.width * gl.r,
            ),
        )
    }
    if (scrim > 0f) drawRect(p.bg.copy(alpha = scrim))
}

/**
 * 读用户那张图的明暗极值（存图时量好写在旁边的 `bg.extremes` 里）。
 *
 * 为什么不在渲染时扫位图：那是每帧都要做的事，`getPixel` 扫几十万像素会直接掉帧；
 * 存图时量一次（每 4 像素取一个）就够，代价只有换图那一瞬间。
 */
@Composable
private fun rememberPhotoExtremes(key: String): Pair<Color, Color>? {
    val ctx = LocalContext.current
    val state = produceState<Pair<Color, Color>?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { BackgroundPhoto.readExtremes(ctx) }
    }
    return state.value
}

/**
 * 读用户那张背景图。
 *
 * `key` 里带着**写入时刻**（`photo:<毫秒>`）：文件名是固定的 `bg.jpg`，
 * 靠这个键才知道"图换了、得重新解码"，否则换图后还显示旧的那张。
 */
@Composable
private fun rememberBackgroundPhoto(key: String): ImageBitmap? {
    val ctx = LocalContext.current
    val state = produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { BackgroundPhoto.load(ctx)?.asImageBitmap() }
    }
    return state.value
}

/**
 * 主题档的存放处（「外观」页写它，`resolvePalette` 读它）。
 *
 * 为什么用 SharedPreferences 而不是 Room：宿主 `MainActivity` 要**在组合之前**就知道
 * 该用哪套色板（状态栏/导航栏要跟主题一致），那时数据库还没起来。
 */
object AppearanceStore {
    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"

    /** 背景三层：默认纯色 → 预设（`preset:<id>`）→ 自己上传（`photo:<写入时刻>`） */
    const val BG_SOLID = "solid"
    const val BG_PHOTO = "photo"

    private const val FILE = "campus_appearance"
    private const val K_THEME = "theme"
    private const val K_BG = "background"
    private const val K_ACCENT = "accent"
    private const val K_BG_ALPHA = "bg_alpha"

    /** 组合侧读这个（`mutableStateOf`）→ 改档位当场重组合 */
    val theme = mutableStateOf(DARK)
    val background = mutableStateOf(BG_SOLID)

    /**
     * 背景图的不透明度（0.15~1.0）。用户会想把图调淡一点、字更好读 —— 这是"自由调"。
     * 只作用于背景图那一层，不影响渐变预设。
     */
    val bgAlpha = mutableStateOf(0.5f)

    /** 主题/强调色规格（`default` / `preset:<id>` / `diy:<hue>:<tint>` / `wallpaper`） */
    val accent = mutableStateOf(AccentSpec.DEFAULT)


    private var bound = false

    fun bind(ctx: Context) {
        if (bound) return
        bound = true
        theme.value = read(ctx)
        background.value = backgroundOf(ctx)
        accent.value = accentOf(ctx)
        bgAlpha.value = bgAlphaOf(ctx)
        TextTint.state.value = TextTint.read(ctx)
        TextTint.widgetState.value = TextTint.readWidget(ctx)
    }

    // 默认 50%（2026-10-18 用户："图片浓淡默认改成百分之五十"）：满图的字太糊，太淡又白设了
    fun bgAlphaOf(ctx: Context): Float = prefs(ctx).getFloat(K_BG_ALPHA, 0.5f).coerceIn(0.15f, 1f)

    fun setBgAlpha(ctx: Context, v: Float) {
        val a = v.coerceIn(0.15f, 1f)
        prefs(ctx).edit().putFloat(K_BG_ALPHA, a).apply()
        bgAlpha.value = a
    }

    fun accentOf(ctx: Context): String = prefs(ctx).getString(K_ACCENT, AccentSpec.DEFAULT) ?: AccentSpec.DEFAULT

    /** 只改内存快照（拖动 DIY 色相时用，松手再落盘，免得每动一格写一次磁盘） */
    fun previewAccent(value: String) {
        accent.value = value
    }

    fun setAccent(ctx: Context, value: String) {
        prefs(ctx).edit().putString(K_ACCENT, value).apply()
        bound = true
        accent.value = value
        pushWidget(ctx)
    }

    fun read(ctx: Context): String = prefs(ctx).getString(K_THEME, DARK) ?: DARK

    fun setTheme(ctx: Context, value: String) {
        prefs(ctx).edit().putString(K_THEME, value).apply()
        bound = true
        theme.value = value
        pushWidget(ctx)
    }

    /**
     * 主题一改就让桌面重画（2026-10-19 用户："小控件以及 app 内容和主题有改动时要实时刷新，
     * 不要有信息错误或是延迟"）。
     *
     * 以前只改了 App 内的状态：**App 当场变、桌面要等下一次 30 分钟刷新** ——
     * 两处看到两套颜色，用户会当成"信息错误"。写偏好和推桌面必须在同一个动作里。
     */
    private fun pushWidget(ctx: Context) {
        runCatching { top.ccbase.campus.widget.TodayWidgetProvider.refresh(ctx) }
    }

    fun backgroundOf(ctx: Context): String = prefs(ctx).getString(K_BG, BG_SOLID) ?: BG_SOLID

    /**
     * 换背景。切走"自己上传的那张"时**顺手删掉本机文件**（7MB 量级，留着没意义）；
     * 切到 `photo:` 时调用方负责先把图存好（`BackgroundPhoto.save`）。
     */
    fun setBackground(ctx: Context, value: String) {
        val prev = background.value
        prefs(ctx).edit().putString(K_BG, value).apply()
        // 动态图片（animphoto:）用的还是同一张 bg.jpg，别把它删了
        if (!value.startsWith(BG_PHOTO) && !value.startsWith(ANIMPHOTO_PREFIX)) BackgroundPhoto.clear(ctx)
        bound = true
        background.value = value
        // 换了一类背景（预设 ↔ 照片 ↔ 动图/视频 ↔ 动态）就把浓淡拉回 **50%**（用户 2026-10-18：
        // "浓淡默认为 50"）。上一张图调过 15% 的话，新背景一上来就淡到看不清，用户以为是"没生效"。
        if (prev != value && kindOf(prev) != kindOf(value)) {
            prefs(ctx).edit().putFloat(K_BG_ALPHA, 0.5f).apply()
            bgAlpha.value = 0.5f
        }
    }

    /** 背景的"类别"：同类别之间来回换不动浓淡，跨类别才重置 */
    private fun kindOf(v: String): String = when {
        v.startsWith(ANIM_PREFIX) -> "anim"
        v.startsWith(UserMedia.PREFIX) -> "media"
        v.startsWith(BG_PHOTO) || v.startsWith(ANIMPHOTO_PREFIX) -> "photo"
        v.startsWith(BG_PRESET_PREFIX) -> "preset"
        else -> v
    }

    /** 档位名 → 界面上的中文（三档写在一处，免得两个页面写法不一致） */
    fun label(v: String): String = when (v) {
        LIGHT -> "浅色"
        SYSTEM -> "跟随系统"
        else -> "深色"
    }

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 整页底。**有背景图时不能让页面再画一层不透明底色** —— 画了就等于把背景层整个盖住
 * （表现为"设了背景图但哪一页都看不见"）。所以页面容器统一用它，别自己写 `.background(C.bg)`；
 * 谁再写回去，`AppearanceGuardTest` 会拦下。
 *
 * 读的是快照状态：加背景图之后所有页面自动重组合，不需要谁去通知它们。
 */
val Modifier.pageSurface: Modifier
    get() = if (AppearanceStore.background.value == AppearanceStore.BG_SOLID) background(C.bg) else this

/**
 * 整屏**浮层**的底：**始终不透明**（[C.bg]）。
 *
 * 与 [pageSurface] 正好相反 —— 浮层存在的意义就是盖住下面的页面，
 * 透过去就会出现"两层字叠在一起"（本项目真出过：CrawlScreen / PlanScreen / SafetyScreen
 * 那几个整屏浮层，`CrawlScreenTest` 有断言守着）。
 * 所以整页底只有两个合法写法：页面用 [pageSurface]、浮层用 [overlaySurface]；
 * 谁自己写 `.background(C.bg)`，`AppearanceGuardTest` 会拦下（那种写法在有背景图时会把背景层盖住）。
 */
val Modifier.overlaySurface: Modifier
    get() = background(C.bg)
        // 浮层必须**吃掉空白处的触摸**：背景色只是画出来的，不拦事件，
        // 点在没挂 clickable 的地方会穿透到下面那页（2026-10-18：在外观页点右上角，
        // 结果进了下面「我的」里的教务入口）。所有整屏浮层共用这个修饰符，一处修全部生效。
        .pointerInput(Unit) { detectTapGestures { } }

/** 背景预设的存储前缀（`preset:<id>`），渲染与设置页共用同一个字面量 */
const val BG_PRESET_PREFIX = "preset:"

/**
 * 当前该画哪一层背景 —— 和 [resolvePalette] 一样"只解析一次"，渲染与设置页读同一个值。
 */
@Composable
fun resolveBackground(): String {
    // 同 [resolvePalette]：启动时读一次，这里只读快照。换背景靠 `AppearanceStore.background` 触发重组合。
    return AppearanceStore.background.value
}
