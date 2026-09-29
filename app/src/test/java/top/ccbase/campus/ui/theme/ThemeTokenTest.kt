package top.ccbase.campus.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题色令牌的使用规矩（源码级守卫）。
 *
 * `C.card / C.cardHi / C.line / C.lineHi` 这几个令牌**本身就是半透明白**
 * （见 Theme.kt：`0x0AFFFFFF` 这类，靠 alpha 表达"比底再亮一点点"）。
 *
 * 坑：`C.card.copy(alpha = 0.6f)` 不会"让它更亮一点" —— `copy` 是把 alpha
 * **覆盖**掉，于是它从"4% 的白"变成"60% 的白"，在深底上就是一块**浅灰砖**。
 * 浅灰底 + 灰字 = 字糊在里面。2026-09-17 用户两次截图说「看不清」，
 * 一次是任务页的筛选片，一次是课表里的空档格，根因都是这一行。
 *
 * 要更亮的底，用现成的 `C.cardHi`（5.8%）/ `C.lineHi`（14%），或者写一个
 * 不透明的颜色再调 alpha。这条测试把规矩钉死。
 */
class ThemeTokenTest {

    private val alphaCarrying = listOf("card", "cardHi", "line", "lineHi")

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
    fun `令牌的强度关系没被改乱`() {
        // 底越往后越亮；字越往后越暗。改配色时这两条一破，界面就会"有的地方糊、有的地方脏"。
        assertTrue("C.card 必须比 C.cardHi 淡", C.card.alpha < C.cardHi.alpha)
        assertTrue("C.line 必须比 C.lineHi 淡", C.line.alpha < C.lineHi.alpha)
        assertTrue("这几个令牌必须仍是半透明的（不透明说明 alpha 被覆盖坏了）",
            listOf(C.card, C.cardHi, C.line, C.lineHi).all { it.alpha < 0.5f })
        assertTrue("字号令牌：C.txt 必须比 C.txt2 亮、C.txt2 比 C.txt3 亮",
            C.txt.red + C.txt.green + C.txt.blue > C.txt2.red + C.txt2.green + C.txt2.blue &&
                C.txt2.red + C.txt2.green + C.txt2.blue > C.txt3.red + C.txt3.green + C.txt3.blue)
        assertTrue("语义色必须是不透明的（否则当底色会糊）",
            listOf(C.violet, C.cyan, C.amber, C.green, C.red, C.magenta).all { it.alpha > 0.99f })
    }
}
