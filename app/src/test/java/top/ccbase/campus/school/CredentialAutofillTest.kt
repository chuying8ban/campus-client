package top.ccbase.campus.school

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 *
 * 2026-11-02 追加：融合门户与教务是**两套口令**，门户页被自动填了教务密码。
 * 这里钉住三件事：① 旧版单槽只迁到教务槽；② 门户页在没有门户凭据时只填学号；
 * ③ 门户槽读不到教务槽里的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialAutofillTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("school_cred", Context.MODE_PRIVATE)

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

    @Test
    fun `门户页没有门户密码时只填学号且清空密码框`() {
        val js = CredentialVault.ssoAutofillJs("2026015842", null)
        assertTrue("学号要填", js.contains("2026015842"))
        assertTrue("密码字面量必须是 null", js.contains("P = null"))
        assertTrue("要回报只填了学号", js.contains("filled-user-only"))
        assertTrue(
            "密码框一个字都不许写：唯一一次 set.call(p,..) 必须在 P===null 之后",
            js.indexOf("set.call(p,") > js.indexOf("if (P === null)"),
        )
        assertTrue(
            "只填学号必须在自动提交之前 return，不能落到 b.click()",
            js.indexOf("filled-user-only") in 1 until js.indexOf("b.click()"),
        )
    }

    @Test
    fun `门户页有门户密码时正常填并等验证码`() {
        val js = CredentialVault.ssoAutofillJs("2026015842", "portal-pass")
        assertTrue(js.contains("portal-pass"))
        assertTrue(js.contains("filled-need-captcha"))
        assertTrue(
            "有验证码框必须在自动提交之前 return",
            js.indexOf("filled-need-captcha") in 1 until js.indexOf("b.click()"),
        )
    }

    @Test
    fun `旧版单槽只迁到教务槽绝不进门户槽`() {
        prefs.edit().clear().putString("blob", "legacy-iv:legacy-ct").commit()
        assertTrue("旧的那份迁到教务", CredentialVault.has(ctx, CredentialVault.Realm.EAMS))
        assertFalse("门户槽不能被旧凭据污染", CredentialVault.has(ctx, CredentialVault.Realm.PORTAL))
        assertTrue("旧键迁完即删", prefs.getString("blob", null) == null)
        assertTrue("迁过来的是原值", prefs.getString("blob_eams", null) == "legacy-iv:legacy-ct")
    }

    @Test
    fun `清掉教务槽不影响门户槽`() {
        prefs.edit().clear()
            .putString("blob_eams", "a:b")
            .putString("blob_portal", "c:d")
            .commit()
        CredentialVault.clear(ctx, CredentialVault.Realm.EAMS)
        assertFalse(CredentialVault.has(ctx, CredentialVault.Realm.EAMS))
        assertTrue("另一个系统那份必须留着", CredentialVault.has(ctx, CredentialVault.Realm.PORTAL))
        CredentialVault.clearAll(ctx)
        assertFalse(CredentialVault.hasAny(ctx))
    }
}
