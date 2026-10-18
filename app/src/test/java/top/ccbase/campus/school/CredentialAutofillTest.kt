package top.ccbase.campus.school

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动填表脚本的转义（2026-10-19 用户："我不是做了自动登录吗，为什么这里又要让我输账号密码"）。
 *
 * 密码里带引号/反斜杠/换行是常见的，转义漏一个，注入的 JS 直接就语法错误 ——
 * 用户看到的现象正是"什么都没发生，还是登录页"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialAutofillTest {

    @Test
    fun `引号与反斜杠不能把脚本打断`() {
        val js = CredentialVault.autofillJs("2026015842", "a'b\\c")
        assertTrue(js.contains("2026015842"))
        assertTrue("单引号必须转义", js.contains("a\\'b"))
        assertFalse("不能出现未转义的裸引号把字符串截断", js.contains("'a'b"))
    }

    @Test
    fun `脚本按占位符找输入框并按原生 setter 写值`() {
        val js = CredentialVault.autofillJs("u", "p")
        assertTrue("要按 placeholder 认用户名框", js.contains("用户名|账号|学号"))
        assertTrue("必须用原生 setter，否则 Vue 拿不到值", js.contains("HTMLInputElement.prototype"))
        assertTrue("要自动点登录", js.contains("click()"))
    }
}
