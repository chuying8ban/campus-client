package top.ccbase.campus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题色令牌的守卫（源码级 + 数值级）。
 *
 * 一、**自带 alpha 的令牌不许再 `copy(alpha=)`**：
 * `C.card / C.cardHi / C.line / C.lineHi` 本身就是半透明白（见 Theme.kt：`0x0AFFFFFF` 这类，
 * 靠 alpha 表达"比底再亮一点点"）。`C.card.copy(alpha = 0.6f)` 不是"让它更亮一点"，
 * 而是把 alpha **覆盖**掉 —— 从"4% 的白"变成"60% 的白"，在深底上就是一块**浅灰砖**。
 * 浅灰底 + 灰字 = 字糊在里面。2026-09-17 用户两次截图说「看不清」，
 * 一次是任务页的筛选片，一次是课表里的空档格，根因都是这一行。
 *
 * 二、**两套色板各自都要过令牌关系 + 对比度**（原来只验了默认那套）：
 * 加了浅色主题之后，"字比底亮"这条不再是普遍规律（浅色里字是**暗**的），
 * 所以判据改成**与底色的对比度**：txt/txt2/语义色 ≥ 4.5、txt3 ≥ 3.0。
 * 阈值不是拍的：深色现状实测 txt 16.25 / txt2 7.67 / txt3 3.79 / violet 5.70 /
 * cyan 12.47 / green 11.03 / amber 11.23 / red 7.13；浅色实测 16.46 / 8.54 / 4.93 /
 * violet 6.61 / magenta 5.46 / cyan 5.09 / amber 4.69 / red 5.23 / green 4.66。
 *
 * 三、**背景图模式（叠 scrim）按最坏情况验**：见 Theme.kt 的 `SCRIM_ALPHA` 两张表。
 * **两套色板的最坏方向相反**（深色怕纯白图、浅色怕纯黑图），所以是"两套 × 两种极端图"四组，
 * 且三级灰字的下界**按色板分开**（深色 2.2 / 浅色 3.0）—— 一刀切会把这层事实抹掉。
 *
 * 四、**数调用点的口径写死**（评审/回归时照这个报，别一会儿一个数）：
 *
 *     git grep -o "C\.[a-zA-Z]*" -- 'app/src/main/**/*.kt' | wc -l
 *
 * ⚠️ **这个数含注释命中**（`C.` 出现在注释里也算，所以换色板时我加的 KDoc 也会让它涨）。
 * 本仓实测：换色板前 **699** → 换后 **687**；差值 12 = `Theme.kt` 自己那 **14** 处 `C.x`
 * 变成了 `p.x`（`schemeFor(p)`），抵掉注释里新增的 2 处。
 * ⇒ **真正的验收口径不是"总数不变"，而是「除 `Theme.kt` 外一个调用点没动」＝ 685 → 685**
 * （那 699/687 里的 14 全部落在 `Theme.kt` 内）。只数代码行会得到另一个数（@researcher 量到 680），
 * 所以**报数时必须写清用的是哪个口径** —— 213/217、`#token=` 被注释规则吃掉那两回，都是口径没说清。
 */
class ThemeTokenTest {

    private val alphaCarrying = listOf("card", "cardHi", "line", "lineHi")

    /** 两套色板都跑一遍：只验默认那套的话，新加一套配色改坏了没人知道。 */
    private val palettes = listOf("深色" to DarkPalette, "浅色" to LightPalette)

    @Test
    fun `自带 alpha 的色令牌不许再用 copy(alpha 覆盖`() {
        val re = Regex("""C\.(card|cardHi|line|lineHi)\s*\.copy\(\s*alpha""")
        val root = File("src/main/java/top/ccbase/campus")
        assertTrue("找不到源码目录（测试的工作目录变了？）", root.isDirectory)

        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                // 注释里为了说明这个坑会写「曾经写成 copy(alpha = .6f)」，不是真代码
                val code = raw.substringBefore("//")
                if (re.containsMatchIn(code)) {
                    bad += "${f.name}:${i + 1}  ${code.trim()}"
                }
            }
        }
        assertTrue(
            "这几个令牌自带 alpha，copy(alpha=) 会把它覆盖掉，把底变成「N% 的白」→ 界面糊掉。\n" +
                "要更亮的底就用 C.cardHi / C.lineHi：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `两套色板都过令牌关系与对比度`() {
        val fail = mutableListOf<String>()

        palettes.forEach { (name, p) ->
            // 底：越"高"的档越亮一点；都必须仍是半透明（不透明说明 alpha 被覆盖坏了）
            if (!(p.card.alpha < p.cardHi.alpha)) fail += "$name · card 必须比 cardHi 淡"
            if (!(p.line.alpha < p.lineHi.alpha)) fail += "$name · line 必须比 lineHi 淡"
            if (!listOf(p.card, p.cardHi, p.line, p.lineHi).all { it.alpha < 0.5f }) {
                fail += "$name · card/cardHi/line/lineHi 必须仍是半透明的"
            }
            // 语义色必须不透明（否则当底色会糊）
            listOf("violet" to p.violet, "cyan" to p.cyan, "amber" to p.amber,
                "green" to p.green, "red" to p.red, "magenta" to p.magenta).forEach { (k, v) ->
                if (v.alpha <= 0.99f) fail += "$name · 语义色 $k 必须不透明"
            }
            // 三级文字的层次：**与底的对比度**必须递减（浅色里字是暗的，所以不能比亮度）
            val cTxt = contrast(p.txt, p.bg)
            val cTxt2 = contrast(p.txt2, p.bg)
            val cTxt3 = contrast(p.txt3, p.bg)
            if (!(cTxt > cTxt2 && cTxt2 > cTxt3)) {
                fail += "$name · txt>txt2>txt3 的对比度层次被改乱（实测 %.2f / %.2f / %.2f）"
                    .format(cTxt, cTxt2, cTxt3)
            }
            // 阈值（纯色底）
            listOf("txt" to cTxt to 4.5, "txt2" to cTxt2 to 4.5, "txt3" to cTxt3 to 3.0).forEach { (pair, min) ->
                val (k, v) = pair
                if (v < min) fail += "$name · %s 对 bg 只有 %.2f（要 ≥ %.1f）".format(k, v, min)
            }
            listOf("violet" to p.violet, "magenta" to p.magenta, "cyan" to p.cyan,
                "amber" to p.amber, "red" to p.red, "green" to p.green).forEach { (k, v) ->
                val c = contrast(v, p.bg)
                if (c < 4.5) fail += "$name · 语义色 %s 对 bg 只有 %.2f（要 ≥ 4.5）".format(k, c)
            }

            // 面板上的字：card/cardHi/bgSoft 都是**半透明底**（盖上 bg 之后才是真正的底色），
            // 2026-09-17 那两次"看不清"就是"面板底 + 灰字"这一类 —— 所以字要在**叠过面板的等效底色**上再验一次。
            listOf(
                "bgSoft" to p.bgSoft,
                "card" to p.card,
                "cardHi" to p.cardHi,
            ).forEach { (panelName, panel) ->
                val eff = over(panel, p.bg)
                listOf("txt" to p.txt to 4.5, "txt2" to p.txt2 to 4.5, "txt3" to p.txt3 to 3.0)
                    .forEach { (pair, min) ->
                        val (k, v) = pair
                        val c = contrast(v, eff)
                        if (c < min) {
                            fail += "$name · 面板 $panelName 上的 %s 只有 %.2f（要 ≥ %.1f，等效底色 #%06X）"
                                .format(k, c, min, eff.toArgb() and 0xFFFFFF)
                        }
                    }
            }
        }

        assertTrue(
            "色板不达标：改配色时破的是这几条 ——\n" + fail.joinToString("\n"),
            fail.isEmpty(),
        )
    }

    @Test
    fun `背景图叠 scrim 后主要文字仍达标`() {
        val fail = mutableListOf<String>()
        // 最坏情况：图是纯白或纯黑 —— **两套色板的最坏方向相反**（深色怕白图、浅色怕黑图），
        // 所以两套 × 两个图各跑一遍（两张表的实测值在 Theme.kt 的 SCRIM_ALPHA 注释里）。
        val worst = listOf("纯白图" to Color.White, "纯黑图" to Color.Black)

        palettes.forEach { (name, p) ->
            // 三级灰字的下界**按色板分开**：深色叠白图只能到 2.32（要 3.0 需 scrim 0.90、图就剩 10% 可见，
            // 见 Theme.kt 那张表），浅色叠黑图仍有 3.25 ⇒ 浅色不跟着放宽。一刀切会把这段事实抹掉。
            val minTxt3 = if (p.bg.luminance() > 0.5) 3.0 else 2.2
            worst.forEach { (imgName, img) ->
                val eff = blend(p.bg, img, SCRIM_ALPHA)
                val cTxt = contrast(p.txt, eff)
                val cTxt2 = contrast(p.txt2, eff)
                val cTxt3 = contrast(p.txt3, eff)
                if (cTxt < 4.5) fail += "$name 叠$imgName · txt %.2f（要 ≥ 4.5，scrim 太薄）".format(cTxt)
                if (cTxt2 < 4.5) fail += "$name 叠$imgName · txt2 %.2f（要 ≥ 4.5，scrim 太薄）".format(cTxt2)
                if (cTxt3 < minTxt3) {
                    fail += "$name 叠$imgName · txt3 %.2f（要 ≥ %.1f）".format(cTxt3, minTxt3)
                }
            }
        }

        assertTrue(
            "背景图模式会糊字：SCRIM_ALPHA=%.2f 在最坏情况下不达标 ——\n".format(SCRIM_ALPHA) +
                fail.joinToString("\n") + "\n（要么提高 SCRIM_ALPHA，要么回退这套配色）",
            fail.isEmpty(),
        )
    }

    /**
     * `C` 的字段必须是**现读当前色板**：换主题靠 `applyPalette` 改快照状态，
     * 699 处 `C.x` 调用点靠这条机制自动跟着变。
     *
     * 这条测试是防退化的：谁把 `C.bg` 改回 `val bg = Color(...)`（类初始化时定死），
     * 界面就会"切了主题但颜色不动" —— 那种 bug 只有当用户点一下才发现。
     */
    @Test
    fun `C 的字段跟着当前色板走`() {
        val before = C.bg
        try {
            applyPalette(LightPalette)
            assertEquals("换了色板，C.bg 必须跟着变", LightPalette.bg, C.bg)
            assertEquals("换了色板，C.txt 必须跟着变", LightPalette.txt, C.txt)
            assertEquals("换了色板，C.card 必须跟着变", LightPalette.card, C.card)
        } finally {
            applyPalette(DarkPalette)
        }
        assertEquals("恢复默认色板后 C.bg 应回到深色", before, C.bg)
    }

    // ── 对比度（WCAG 相对亮度）───────────────────────────────────────────────

    private fun channel(v: Float): Double =
        if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** scrim = 底色以 alpha 盖在图上 → 等效底色 = 图*(1-alpha) + 底*alpha（sRGB 直混，与 GPU 一致） */
    private fun blend(base: Color, over: Color, alpha: Float): Color = Color(
        red = over.red * (1 - alpha) + base.red * alpha,
        green = over.green * (1 - alpha) + base.green * alpha,
        blue = over.blue * (1 - alpha) + base.blue * alpha,
        alpha = 1f,
    )

    /** 半透明面板盖在底色上 → 真正的等效底色（同上，sRGB 直混） */
    private fun over(panel: Color, base: Color): Color {
        val a = panel.alpha
        return Color(
            red = panel.red * a + base.red * (1 - a),
            green = panel.green * a + base.green * (1 - a),
            blue = panel.blue * a + base.blue * (1 - a),
            alpha = 1f,
        )
    }
}
