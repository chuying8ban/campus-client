package top.ccbase.campus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * 学生面报错文案的契约（对应服务端的 `test_student_errors.py`）。
 *
 * 三件事缺一不可：
 *  ① 两句文案的**字面值**钉死 —— 服务端 `errors.py` 里写的是同一个串，
 *     谁改一边忘了另一边，学生就会同时看到两种说法（所以这里断言的是具体汉字，不是常量自比）；
 *  ② 通用失败只出一句话，且**不许**带异常类名/接口路径/状态码；
 *  ③ 原文必须留在 logcat 里 —— 收敛的前提是"还有地方能查"，不然就是拿掉排查手段。
 */
@RunWith(RobolectricTestRunner::class)
class StudentErrorTest {

    @Test
    fun `A串必须与服务端逐字相同`() {
        // 服务端 errors.py: STUDENT_ERROR_TEXT = "暂时无法完成，请稍后重试"
        assertEquals("暂时无法完成，请稍后重试", StudentError.TEXT)
    }

    @Test
    fun `401串必须与服务端逐字相同`() {
        // 服务端 errors.py: SESSION_EXPIRED_TEXT；也是 RefreshBar 一直在用的原话
        assertEquals("登录已过期，请重新登录", StudentError.SESSION)
    }

    @Test
    fun `B串指向 App 内真实存在的入口`() {
        // 入口真名是「给 App 提建议」（我的 → 关于），写「反馈」学生找不到
        assertTrue("B 串必须点到真入口：${StudentError.HOW}", StudentError.HOW.contains("给 App 提建议"))
    }

    @Test
    fun `技术性失败只出一句话_但原文进 logcat`() {
        ShadowLog.clear()
        val msg = StudentError.tech(RuntimeException("内部细节：/api/v2/plan 的 courseName 为空"), "/api/v2/plan")
        assertEquals(StudentError.TEXT, msg)
        val logs = ShadowLog.getLogs()
        assertTrue("原文必须留痕", logs.any { it.tag == "StudentError" && it.msg.contains("/api/v2/plan") })
        assertTrue("异常本体也要留（带堆栈）", logs.any { it.throwable is RuntimeException })
    }

    @Test
    fun `空白detail的状态码进日志不进文案`() {
        ShadowLog.clear()
        assertEquals(StudentError.TEXT, StudentError.http(502, "/api/v2/plan"))
        assertTrue(ShadowLog.getLogs().any { it.tag == "StudentError" && it.msg.contains("502") })
    }

    @Test
    fun `整屏错误页只有在通用失败时才补导航`() {
        // 恰好是那句通用报错 → 补 B
        assertEquals("${StudentError.TEXT}\n${StudentError.HOW}", StudentError.screenText(StudentError.TEXT))
        // 服务端给的产品提示（自己已经说清下一步）→ 原样，不硬接导航
        assertEquals("登录已过期，请重新登录", StudentError.screenText(StudentError.SESSION))
        assertEquals("还没写内容呢", StudentError.screenText("还没写内容呢"))
    }

    @Test
    fun `两句文案里不许出现任何内部痕迹`() {
        val words = listOf("/api/", "http", "Exception", "courseName", "token", "令牌", "grab", "null")
        for (s in listOf(StudentError.TEXT, StudentError.HOW, StudentError.SESSION)) {
            for (w in words) {
                assertFalse("文案「$s」里出现了内部痕迹「$w」", s.contains(w, ignoreCase = true))
            }
        }
    }
}
