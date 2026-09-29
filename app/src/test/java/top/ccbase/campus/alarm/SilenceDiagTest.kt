package top.ccbase.campus.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动静音自诊断的测试。
 *
 * 这一页存在的理由：静音失败在真机上就是"什么都没发生"，
 * 用户看不到也说不清。所以这里的核心断言不是"函数返回 true"，
 * 而是**"出问题时人能读出一句能照着修的话"**。
 */
class SilenceDiagTest {

    private fun tmp(): File = File.createTempFile("diag", "").let {
        it.delete(); it.mkdirs(); it
    }

    // ------------------------------------------------------- 结论：会不会静音

    @Test
    fun `权限齐了_结论就是具备生效条件`() {
        val items = SilenceDiag.items(DiagSnapshot(true, true, true, true, true))
        assertEquals("静音具备生效条件", SilenceDiag.verdict(items))
    }

    @Test
    fun `缺修改系统设置_结论要点名是哪一项`() {
        // 这是用户最该一眼看到的信息：不是"失败了"，而是"缺哪一项"
        val items = SilenceDiag.items(DiagSnapshot(false, true, true, true, true))
        val v = SilenceDiag.verdict(items)
        assertTrue("结论没点名缺的那项，实际：$v", v.contains("修改系统设置"))
        assertFalse("权限齐了却说不会生效", v.contains("具备生效条件"))
    }

    @Test
    fun `一次缺多项_结论要全部列出来`() {
        val items = SilenceDiag.items(DiagSnapshot(false, false, false, false, false))
        val v = SilenceDiag.verdict(items)
        listOf("修改系统设置", "勿扰访问", "闹钟与提醒", "电池白名单").forEach {
            assertTrue("结论漏了「$it」：$v", v.contains(it))
        }
    }

    @Test
    fun `还没体检时不许假装一切正常`() {
        assertEquals("还没体检", SilenceDiag.verdict(emptyList()))
    }

    // ------------------------------------------------------- 体检项本身

    @Test
    fun `体检五项_每项都要说清缺了会怎样`() {
        val items = SilenceDiag.items(DiagSnapshot(false, false, false, false, false))
        assertEquals(5, items.size)
        items.forEach {
            assertFalse("「${it.name}」没说后果", it.detail.isBlank())
            assertFalse("「${it.name}」的说明把内部错误码直接甩给用户了", it.detail.startsWith("no_"))
            assertTrue("「${it.name}」缺了却没有可去的地方", it.action.isNotBlank())
        }
    }

    @Test
    fun `电池白名单缺失要提到小米这类ROM`() {
        // 国产 ROM 杀后台是真实原因之一，说明里不点出来用户想不到
        val d = SilenceDiag.items(DiagSnapshot(true, true, true, false, true))
            .first { it.name == "电池白名单" }.detail
        assertTrue("没提醒 ROM 差异：$d", d.contains("HyperOS") || d.contains("小米"))
    }

    // ------------------------------------------------------- 失败原因的人话

    @Test
    fun `每个失败码都有人话解释_且不能原样返回内部码`() {
        listOf("no_write_settings", "no_dnd_access", "no_exact_alarm", "no_alarm_set", "exception")
            .forEach {
                val s = SilenceDiag.explain(it)
                assertTrue("$it 没解释", s.isNotBlank())
                assertFalse("$it 把内部码原样返回了", s == it)
            }
    }

    // ------------------------------------------------------- 落盘记录

    @Test
    fun `记录能落盘也能读回_最近的排最前`() {
        val dir = tmp()
        DiagLog.append(dir, Attempt("2026-09-17 08:44", "课前静音", true))
        DiagLog.append(dir, Attempt("2026-09-17 08:44", "课前静音", false, "no_write_settings"))
        DiagLog.append(dir, Attempt("2026-09-17 09:30", "下课恢复", true))

        val back = DiagLog.read(dir)
        assertEquals(3, back.size)
        assertEquals("下课恢复", back.first().what)          // 最近的在最前
        assertFalse(back[1].ok)
        assertEquals("no_write_settings", back[1].reason)
    }

    @Test
    fun `出问题时能从文件里读出为什么`() {
        // 端到端：写一条失败的 → 读回来 → 能翻译成一句人话
        val dir = tmp()
        DiagLog.append(dir, Attempt(DiagLog.now(), "课前静音·数学分析（I）", false, "no_write_settings"))
        val last = DiagLog.read(dir, limit = 1).single()
        assertFalse(last.ok)
        assertTrue("课程名丢了，没法判断是哪节课", last.what.contains("数学分析（I）"))
        assertTrue(SilenceDiag.explain(last.reason).contains("修改系统设置"))
    }

    @Test
    fun `记录里的换行与制表符不许破坏一行一条`() {
        val dir = tmp()
        DiagLog.append(dir, Attempt("2026-09-17 08:44", "课前静音", false, "原因\n带换行\t和制表"))
        val lines = File(dir, "silence_log.txt").readLines().filter { it.isNotBlank() }
        assertEquals("一条记录被撑成了多行，读回来就会错位", 1, lines.size)
        assertEquals(1, DiagLog.read(dir).size)
    }

    @Test
    fun `记录不会无限增长`() {
        val dir = tmp()
        repeat(520) { DiagLog.append(dir, Attempt("2026-09-17 08:44", "课前静音", true)) }
        val lines = File(dir, "silence_log.txt").readLines().filter { it.isNotBlank() }
        assertTrue("条数没有上限，长期会拖慢读写：${lines.size}", lines.size <= 500)
    }

    @Test
    fun `坏行不会让整份记录读不出来`() {
        val dir = tmp()
        DiagLog.append(dir, Attempt("2026-09-17 08:44", "课前静音", true))
        File(dir, "silence_log.txt").appendText("这不是一条合法记录\n\n")
        assertEquals(1, DiagLog.read(dir).size)
    }

    @Test
    fun `没有记录文件时读出空表而不是崩`() {
        assertEquals(0, DiagLog.read(tmp()).size)
    }

    @Test
    fun `解析失败的行返回空`() {
        assertEquals(null, SilenceDiag.parse(""))
        assertEquals(null, SilenceDiag.parse("只有一段"))
        assertNotNull(SilenceDiag.parse("2026-09-17 08:44\t课前静音\t成功"))
    }

    // ------------------------------------------------------- 缺失项的"照着做"

    @Test
    fun `缺权限时必须给出照着做的步骤_而不是只说没授权`() {
        val items = SilenceDiag.items(
            DiagSnapshot(false, false, false, false, false)
        )
        items.forEach { it ->
            if (!it.ok) {
                assertTrue("「${it.name}」只说缺权限、没给步骤，用户在设置里翻不到", it.steps.size >= 3)
                it.steps.forEach { st -> assertTrue("步骤不该是空话：$st", st.length > 8) }
            } else {
                assertTrue("已通过的项不该塞引导，越看越累", it.steps.isEmpty())
            }
        }
    }

    @Test
    fun `勿扰访问的步骤要说清开关名和菜单路径`() {
        val it = SilenceDiag.items(DiagSnapshot(false, false, true, true, true))
            .first { it.name == "勿扰访问" }
        val text = it.steps.joinToString("\n")
        // 页名以用户实机为准：他那台小米上这一页叫「模式访问权限」（2026-09-17 截图），
        // 所以文案要把各品牌叫法都写上，光写一个名字等于把人带沟里。
        assertTrue("必须说清各品牌叫法：$text", text.contains("模式访问权限"))
        assertTrue("要说清去哪一项里找：$text", text.contains("校园"))
        assertTrue("必须点到 App 的名字（系统里叫「校园」）：$text", text.contains("校园"))
        assertTrue("要把后果讲出来（静默丢掉，不报错）：$text", text.contains("静默"))
    }

    @Test
    fun `电池白名单的步骤要给到小米那两处开关`() {
        val it = SilenceDiag.items(DiagSnapshot(true, true, true, false, true))
            .first { it.name == "电池白名单" }
        val text = it.steps.joinToString("\n")
        assertTrue("必须给出「省电策略 → 无限制」：$text", text.contains("无限制"))
        assertTrue("必须给出「自启动」：$text", text.contains("自启动"))
        assertTrue("要给到小米原生入口，光靠系统那页在 HyperOS 上会被拦住：$text", text.contains("应用管理"))
    }

    @Test
    fun `已授权时不要塞引导步骤`() {
        val items = SilenceDiag.items(DiagSnapshot(true, true, true, true, true))
        assertTrue(items.all { it.ok })
        assertTrue(items.all { it.steps.isEmpty() })
    }
}
