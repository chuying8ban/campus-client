package top.ccbase.campus.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.ccbase.campus.net.ApiUser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地登录令牌/访客令牌的保存。
 *
 * 凭据不再以明文 SharedPreferences 落地：Android Keystore 生成不可导出的 AES-256-GCM
 * 密钥，密文 + IV 一并存进 App 私有 SharedPreferences。旧版本留下的明文字段在第一次
 * 访问时原地迁移成密文，随后明文键被删除；迁移完成后的生产路径**没有明文回退**。
 *
 * 测试可以注入 [cipher]（见 [TokenCipher]），避免在 JVM/Robolectric 上依赖真实 Keystore。
 */
object TokenStore {
    private const val TAG = "TokenStore"
    private const val PREF = "campus_session"
    private const val K_ENC = "session_v2"
    private const val K_MIGRATED = "session_migrated_v2"

    // 旧版明文键：只用于一次性迁移。
    private const val K_TOKEN = "token"
    private const val K_EXP = "expires_at"
    private const val K_NAME = "name"
    private const val K_SID = "student_id"
    private const val K_GRAB = "can_grab"

    private val json = Json { ignoreUnknownKeys = true }

    /** 可注入的加解密实现；生产默认 Android Keystore。 */
    @Volatile
    var cipher: TokenCipher = AndroidKeystoreTokenCipher()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(ctx: Context, token: String, expiresAt: String, user: ApiUser) {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        val plain = json.encodeToString(
            StoredSession.serializer(),
            StoredSession(token, expiresAt, user.name.orEmpty(), user.student_id, user.canGrab),
        )
        // 先加密，成功后才构造写入：encrypt 抛异常时这次编辑根本不会发生，
        // 旧键（含可能还没迁移成功的明文）因此被完整保留，而不是被清掉丢令牌。
        val blob = try {
            cipher.encrypt(plain)
        } catch (e: Exception) {
            Log.e(TAG, "加密令牌失败，本次不落盘（保留旧键，绝不回退明文）", e)
            return
        }
        prefs.edit()
            .putString(K_ENC, blob)
            .putBoolean(K_MIGRATED, true)
            // 新密文已是权威来源：顺手清掉可能残留的旧明文键
            .remove(K_TOKEN).remove(K_EXP).remove(K_NAME).remove(K_SID).remove(K_GRAB)
            .apply()
    }

    fun token(ctx: Context): String? {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        return read(ctx, prefs)?.token
    }

    fun studentId(ctx: Context): String? {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        return read(ctx, prefs)?.studentId
    }

    fun name(ctx: Context): String? {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        return read(ctx, prefs)?.name
    }

    fun expiresAt(ctx: Context): String? {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        return read(ctx, prefs)?.expiresAt
    }

    /**
     * 本机记的到期时间是不是已经过了。这是**提前提示**用的近似判断；
     * 真正判据永远是服务端 401。解析不出来时一律当没过期，不能因为格式变了把人挡在外面。
     */
    fun expired(ctx: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean {
        val raw = expiresAt(ctx)?.trim().orEmpty()
        if (raw.isBlank()) return false
        val at = runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toLocalDateTime() }.getOrNull()
            ?: return false
        return !at.isAfter(now)
    }

    /** 抢课权限：只用来决定要不要显示入口，真正边界在服务端。 */
    fun canGrab(ctx: Context): Boolean {
        val prefs = sp(ctx)
        migrateIfNeeded(ctx, prefs)
        return read(ctx, prefs)?.canGrab ?: false
    }

    fun clear(ctx: Context) = sp(ctx).edit().clear().apply()

    private fun read(ctx: Context, prefs: android.content.SharedPreferences): StoredSession? {
        val blob = prefs.getString(K_ENC, null) ?: return null
        return try {
            cipher.decrypt(blob)?.let { json.decodeFromString(StoredSession.serializer(), it) }
        } catch (e: Exception) {
            Log.e(TAG, "令牌解密失败（不会回退到明文）", e)
            null
        }
    }

    /**
     * 一次性迁移旧明文 SharedPreferences。
     *
     * **fail closed**：只有旧的明文**成功加密落盘**之后，才允许删明文键并置迁移标记。
     * 加密失败（Keystore 暂时不可用等）时保留旧键、不置标记 —— 令牌不会丢，
     * 下一次读取会再试；但读取路径**只认密文**，绝不拿明文去发请求。
     * 没有旧明文时直接置标记，避免每次都走进来重试。
     */
    private fun migrateIfNeeded(ctx: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(K_MIGRATED, false)) return
        val oldToken = prefs.getString(K_TOKEN, null)
        if (oldToken.isNullOrBlank()) {
            prefs.edit()
                .remove(K_TOKEN).remove(K_EXP).remove(K_NAME).remove(K_SID).remove(K_GRAB)
                .putBoolean(K_MIGRATED, true)
                .apply()
            return
        }
        val old = StoredSession(
            token = oldToken,
            expiresAt = prefs.getString(K_EXP, "").orEmpty(),
            name = prefs.getString(K_NAME, "").orEmpty(),
            studentId = prefs.getString(K_SID, "").orEmpty(),
            canGrab = prefs.getBoolean(K_GRAB, false),
        )
        val blob = try {
            cipher.encrypt(json.encodeToString(StoredSession.serializer(), old))
        } catch (e: Exception) {
            Log.e(TAG, "旧令牌迁移加密失败：保留旧键待下次重试（不回退明文发请求）", e)
            return
        }
        prefs.edit()
            .putString(K_ENC, blob)
            .remove(K_TOKEN).remove(K_EXP).remove(K_NAME).remove(K_SID).remove(K_GRAB)
            .putBoolean(K_MIGRATED, true)
            .apply()
    }
}

@Serializable
private data class StoredSession(
    val token: String,
    val expiresAt: String,
    val name: String,
    val studentId: String,
    val canGrab: Boolean,
)

/** 可注入的令牌加解密。加密失败应抛异常；解密失败可返回 null 或抛异常。 */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String?
}

/** Android Keystore AES-256-GCM，密钥不可导出，密文带 12 字节随机 IV。 */
class AndroidKeystoreTokenCipher : TokenCipher {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "campus_token_v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String? {
        val all = try {
            Base64.decode(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        if (all.size < IV_SIZE) return null
        val iv = all.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val plain = cipher.doFinal(all, IV_SIZE, all.size - IV_SIZE)
        return String(plain, Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
