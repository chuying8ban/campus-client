package top.ccbase.campus.school

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 「记住账号密码」——**可选**，默认关闭。
 *
 * 为什么需要它：现有的"自动登录"只是**保住会话**（把学校发的 Session Cookie 改写成持久 Cookie），
 * 学校那边一旦让会话过期（树维 EAMS 的 SESSION 通常几小时就到期），客户端没有任何办法凭空续上，
 * 只能重新登录。用户要的是"再也不用输密码"，那就必须本机留一份凭据。
 *
 * 边界：
 *  · 密钥在 Android Keystore 里（硬件支持时不出安全芯片），本文件只存密文；
 *  · 只在**学校的登录页**里自动填 + 自动提交，不做别的自动化；
 *  · 用户随时可以在导入页关掉，一关就连密文一起删；
 *  · 卸载 / 清除数据即消失，不上传、不参与云同步。
 */
object CredentialVault {

    private const val PREFS = "school_cred"
    private const val K_BLOB = "blob"
    private const val KEY_ALIAS = "campus_school_cred"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    /** 存一份（覆盖旧的）。失败返回 false —— 调用方要告诉用户"没存上"。 */
    fun save(ctx: Context, user: String, pass: String): Boolean = runCatching {
        if (user.isBlank() || pass.isEmpty()) return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal("$user\n$pass".toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs(ctx).edit().putString(K_BLOB, blob).commit()
    }.getOrDefault(false)

    fun load(ctx: Context): Pair<String, String>? = runCatching {
        val blob = prefs(ctx).getString(K_BLOB, null) ?: return null
        val (ivB64, ctB64) = blob.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
        )
        val plain = String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        val nl = plain.indexOf('\n')
        if (nl <= 0) return null
        plain.substring(0, nl) to plain.substring(nl + 1)
    }.getOrNull()

    fun has(ctx: Context): Boolean = prefs(ctx).getString(K_BLOB, null) != null

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_BLOB).commit()
    }

    /**
     * 在教务登录页里自动填 + 自动提交。
     *
     * 用 placeholder 找输入框（树维 authc 的 Vue 页面没有稳定的 id），并且用
     * `HTMLInputElement.prototype` 的原生 setter 写值 —— 直接 `el.value = x` 不会触发 Vue 的
     * 双向绑定，点登录时提交的仍然是空串（这是这类自动填充最常见的坑）。
     */
    /**
     * 统一身份认证（CAS）登录页的自动填充（2026-10-19 融合门户改造）。
     *
     * 与教务直登的区别只有两点，但都很要紧：
     *  ① CAS 页有**稳定 id**（`#username` / `#password`），不用按 placeholder 猜；
     *  ② CAS **强制验证码**（`enableCaptcha=Y`）—— 所以**绝不自动提交**，
     *    填完就把光标送进验证码框。自动点「登录」只会被驳回，还会白攒失败次数。
     */
    fun ssoAutofillJs(user: String, pass: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r")
        return """
        (function(){
          try {
            var U = '${esc(user)}', P = '${esc(pass)}';
            var u = document.getElementById('username') || document.querySelector('input[name=username]');
            var p = document.getElementById('password') || document.querySelector('input[name=password]');
            if (!u || !p) return 'noform';
            var set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
            set.call(u, U); u.dispatchEvent(new Event('input',{bubbles:true}));
            u.dispatchEvent(new Event('change',{bubbles:true}));
            set.call(p, P); p.dispatchEvent(new Event('input',{bubbles:true}));
            p.dispatchEvent(new Event('change',{bubbles:true}));
            var c = document.getElementById('captcha');
            if (c) { c.focus(); return 'filled-need-captcha'; }
            // 没验证码框（学校偶尔关掉）才允许提交
            var btns = Array.prototype.slice.call(document.querySelectorAll('button,input[type=submit]'));
            var b = btns.filter(function(e){ return /登\s*录|login/i.test(e.innerText||e.value||''); })[0];
            if (b) { b.click(); return 'clicked'; }
            return 'filled';
          } catch (e) { return 'err:' + e.message; }
        })()
        """.trimIndent()
    }

    fun autofillJs(user: String, pass: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r")
        return """
        (function(){
          try {
            var U = '${esc(user)}', P = '${esc(pass)}';
            var ins = Array.prototype.slice.call(document.querySelectorAll('input'));
            var u = ins.filter(function(i){ return /用户名|账号|学号/.test(i.placeholder||''); })[0];
            var p = ins.filter(function(i){ return /密码/.test(i.placeholder||'') || i.type==='password'; })[0];
            if (!u || !p) return 'noform';
            var set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
            set.call(u, U); u.dispatchEvent(new Event('input',{bubbles:true}));
            set.call(p, P); p.dispatchEvent(new Event('input',{bubbles:true}));
            var btns = Array.prototype.slice.call(document.querySelectorAll('button,.el-button,.sw-button,input[type=submit]'));
            var b = btns.filter(function(e){ return /^\s*登\s*录\s*$/.test(e.innerText||e.value||''); })[0];
            if (!b) return 'filled';
            b.click();
            return 'clicked';
          } catch (e) { return 'err:' + e.message; }
        })()
        """.trimIndent()
    }
}
