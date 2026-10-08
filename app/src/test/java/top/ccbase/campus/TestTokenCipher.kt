package top.ccbase.campus

import top.ccbase.campus.data.remote.TokenCipher
import top.ccbase.campus.data.remote.TokenStore

/**
 * 测试专用：把 [TokenStore.cipher] 换成可逆的假实现。
 *
 * Robolectric 的 JVM 上没有 `AndroidKeyStore`，生产实现 `AndroidKeystoreTokenCipher` 加密必抛异常。
 * 测试**不能**因此让生产代码回退明文；正确的做法是注入一个与生产同接口的 cipher
 * （见 [TokenStore.cipher] 的注释）。
 */
object TestTokenCipher : TokenCipher {
    private const val PREFIX = "TEST-"

    override fun encrypt(plain: String): String =
        PREFIX + java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    override fun decrypt(blob: String): String? =
        if (blob.startsWith(PREFIX)) {
            String(java.util.Base64.getDecoder().decode(blob.substring(PREFIX.length)), Charsets.UTF_8)
        } else {
            null
        }
}

/**
 * Robolectric 全局测试 Application（由 `src/test/resources/robolectric.properties` 指定）。
 * 每个测试进程创建时把 TokenStore 的加解密换成测试实现 —— 一处覆盖所有测试，不必逐个改。
 */
class TestCampusApplication : CampusApplication() {
    override fun onCreate() {
        super.onCreate()
        TokenStore.cipher = TestTokenCipher
    }
}
