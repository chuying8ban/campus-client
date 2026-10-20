package top.ccbase.campus.ui.me

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「我的」页文字颜色的回归测试。
 *
 * 起因：2026-10-20 用户截图指着「自动后台更新」说"这里的字的颜色没有统一"。
 * 根因是同一层级的颜色散成了十几份裸字面量（0.3/0.35/0.38/0.45/0.5/0.55/0.6/0.75/0.85），
 * 自动更新那一段还漏写 color、吃到了 Material 默认的 onSurface。改一处漏一处，于是整页"看着发虚"。
 *
 * 现在全页只允许四档透明度，且只能从 [MeRowStyle] 取。这里把这两条钉住。
 */
class MeRowStyleTest {

    private val onBg = Color(0xFF1A1A1E)
    private val err = Color(0xFFB3261E)

    private fun meScreen(): String {
        val f = File("src/main/java/top/ccbase/campus/ui/me/MeScreen.kt")
        assertTrue("找不到 MeScreen.kt，测试工作目录不对：${File(".").absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `四档透明度要有明确的大小关系`() {
        // 标题 > 正文 > 说明 > 禁用：靠透明度就能读出层级，不需要额外字号/颜色
        assertTrue(
            "层级不对：TITLE=${MeRowStyle.TITLE_ALPHA} BODY=${MeRowStyle.BODY_ALPHA} " +
                "HINT=${MeRowStyle.HINT_ALPHA} DISABLED=${MeRowStyle.DISABLED_ALPHA}",
            MeRowStyle.TITLE_ALPHA > MeRowStyle.BODY_ALPHA &&
                MeRowStyle.BODY_ALPHA > MeRowStyle.HINT_ALPHA &&
                MeRowStyle.HINT_ALPHA > MeRowStyle.DISABLED_ALPHA,
        )
        // 说明文字不能淡到看不见（0.4 ≈ 深底上的 3.0:1，够读但明显是次要信息）
        assertTrue(MeRowStyle.HINT_ALPHA >= 0.4f)
    }

    @Test
    fun `取色结果与声明的档位一致`() {
        // Color 的 alpha 是 8 位存的（0.88f -> 0.8784314），按 1/255 级别容差比较
        assertEquals(MeRowStyle.TITLE_ALPHA, MeRowStyle.titleColor(onBg, err).alpha, 0.005f)
        assertEquals(MeRowStyle.BODY_ALPHA, MeRowStyle.bodyColor(onBg).alpha, 0.005f)
        assertEquals(MeRowStyle.HINT_ALPHA, MeRowStyle.hintColor(onBg).alpha, 0.005f)
        assertEquals(
            MeRowStyle.DISABLED_ALPHA,
            MeRowStyle.titleColor(onBg, err, enabled = false).alpha, 0.005f,
        )
    }

    @Test
    fun `自动后台更新那一段用的是同一套取色`() {
        val src = meScreen()
        val idx = src.indexOf("\"自动后台更新\"")
        assertTrue("源码里找不到「自动后台更新」", idx >= 0)
        // 往后取 900 字，覆盖标题 + 副标题 + 后台保活 + 只在 Wi-Fi 下下载
        val block = src.substring(idx, minOf(src.length, idx + 900))
        assertTrue("自动更新行的标题没走 MeRowStyle.titleColor", block.contains("MeRowStyle.titleColor"))
        assertTrue("自动更新行的副标题没走 MeRowStyle.hintColor", block.contains("MeRowStyle.hintColor"))
    }

    @Test
    fun `MeScreen 里不许再出现裸写的文字透明度`() {
        val bad = Regex("""on(Background|Surface)\.copy\(\s*alpha\s*=""")
            .findAll(meScreen()).map { it.value }.toList()
        assertTrue(
            "MeScreen.kt 里还有裸写的文字透明度（应改成 MeRowStyle.xxx）：$bad",
            bad.isEmpty(),
        )
    }
}
