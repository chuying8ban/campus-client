package top.ccbase.campus.update

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 自动更新里**不依赖 Android** 的那一半：解析、比版本、算校验和、限流。
 *
 * 全部是纯函数，所以能在 JVM 上直接测 —— 更新的正确性不该等到真机上才发现
 * （这个 App 没有模拟器可跑：`cc` 不在 kvm 组，只能靠这类测试兜住）。
 */
object UpdateLogic {

    /** 启动时最多 6 小时查一次；手动点「检查更新」不受限 */
    const val CHECK_GAP_MS = 6 * 60 * 60 * 1000L

    /** 进入 App 时检查的最小间隔：只防连续开关 App 连打请求，不拦正常的每次进来都查 */
    const val ENTRY_GAP_MS = 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true      // 服务端加字段不许让 App 崩
        isLenient = true
        coerceInputValues = true
    }

    /** 解析失败一律返回 null（调用方当作「这次没查到」，绝不弹错误框骚扰） */
    fun parse(text: String): UpdateManifest? = try {
        json.decodeFromString(UpdateManifest.serializer(), text).takeIf { it.looksUsable() }
    } catch (e: Exception) {
        null
    }

    /**
     * 一份 manifest 要能被**信任地去下载**，至少得说清楚：装哪个版本、从哪下、校验和是多少。
     * 缺任何一项都当作没查到 —— 宁可不更新，也不能装一个没法验证来源的包。
     */
    private fun UpdateManifest.looksUsable(): Boolean =
        versionCode > 0 &&
            versionName.isNotBlank() &&
            url.isNotBlank() &&
            sha256.length == 64 &&
            sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** 这次检查该不该跑（限流）；手动检查时传 force = true */
    fun shouldCheckNow(lastCheckMs: Long, nowMs: Long, force: Boolean = false): Boolean =
        force || lastCheckMs <= 0 || nowMs - lastCheckMs >= CHECK_GAP_MS

    /**
     * 进入 App 时该不该查一次。
     *
     * 为什么不复用 [shouldCheckNow]：那个是 6 小时节流 —— 用户一天开几次 App，
     * 每次都被挡住，于是进了 App 也不知道有新版本。用户明确要求：进来就该知道。
     * 这里只留 1 分钟下限（挡住连续开关 App 的重复请求），正常使用几乎每次都查；
     * 代价是几百字节的 manifest，可以忽略。
     */
    fun shouldCheckOnEntry(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs - lastCheckMs >= ENTRY_GAP_MS

    /**
     * 该不该为这个新版本发通知栏提醒。
     *
     * 一个版本只提醒一次（[notifiedCode] 记住提醒过哪个版本）；用户点过「以后再说」的版本
     * 也不再提醒 —— 否则每开一次 App 就被打扰一次，跟更新提示想起到的作用正好相反。
     */
    fun shouldNotify(m: UpdateManifest, currentCode: Int, skippedCode: Int, notifiedCode: Int): Boolean =
        m.versionCode > currentCode && m.versionCode != skippedCode && m.versionCode != notifiedCode

    /**
     * 「我的」页那行提示：有新版本就一直写着，直到版本号追平（追平后本函数返回 null，
     * 不需要谁去清理那笔记录）。
     */
    fun availableHint(currentCode: Int, currentName: String, availableCode: Int, availableName: String?): String? {
        if (availableName.isNullOrBlank() || availableCode <= currentCode) return null
        return "有新版本 v$availableName 可用（当前 $currentName）· 点这里现在就装"
    }

    sealed class Decision {
        /** 已是最新，或者用户说了「以后再说」 */
        data class UpToDate(val reason: String) : Decision()
        data class Optional(val manifest: UpdateManifest) : Decision()
        /** 不给退路：服务端标了 force，或者当前版本低于 min_version_code */
        data class Forced(val manifest: UpdateManifest, val why: String) : Decision()
    }

    /**
     * @param skippedCode 用户点过「以后再说」的那个版本；同样的版本不再重复打扰
     * @param manual 用户**主动**点的「检查更新」。主动问就必须给真实答案：
     *   否则点过「以后再说」的人从此永远看到「最新」，再也发现不了更新
     *   （2026-09-17 同学反馈的真事）。
     */
    fun decide(currentCode: Int, m: UpdateManifest, skippedCode: Int = 0, manual: Boolean = false): Decision {
        if (m.versionCode <= currentCode) return Decision.UpToDate("已是最新版本")
        if (m.force) return Decision.Forced(m, "服务端标记为必须更新")
        if (m.minVersionCode > 0 && currentCode < m.minVersionCode) {
            return Decision.Forced(m, "当前版本太旧（低于 v${m.minVersionCode}），无法继续使用")
        }
        // 「以后再说」只对自动检查生效（见 manual 的说明）
        if (!manual && m.versionCode == skippedCode) return Decision.UpToDate("你选了「以后再说」")
        return Decision.Optional(m)
    }

    /**
     * APK 地址：允许相对路径，按 manifest 所在域解析。
     * 这样换域名时只要 manifest 跟着走，APK 地址不用改。
     */
    fun resolveUrl(base: String, url: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return base.trimEnd('/') + "/" + u.trimStart('/')
    }

    fun manifestUrl(base: String): String = base.trimEnd('/') + "/updates/manifest.json"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 大文件用流式摘要，别把 7MB 全读进内存再算 */
    fun sha256HexOf(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 校验和比对：大小写不敏感，空白容错（服务端手写 manifest 很容易多个空格） */
    fun shaMatches(expected: String, actual: String): Boolean =
        expected.trim().lowercase() == actual.trim().lowercase()

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    /** 「安装未知应用」没授权时该说什么 —— 说清去哪儿点，别让用户自己找 */
    fun installPermissionHint(canInstall: Boolean): String? =
        if (canInstall) null
        else "系统设置里给本应用打开「安装未知应用」权限后，再点一次安装"

    /** 点了「更新」之后、**开始下载之前**要先过的闸门。 */
    enum class UpdateGate { Download, AskInstallPermission }

    /**
     * 安装许可必须在**下载之前**问。
     *
     * 放在下载之后问会白让用户下 7MB、白等一场，最后卡在安装那一步 ——
     * 用户的结论只会是"更新坏了"，而不是"缺个权限"。
     */
    fun gateBeforeDownload(canInstall: Boolean): UpdateGate =
        if (canInstall) UpdateGate.Download else UpdateGate.AskInstallPermission

    /** 「更新前先要许可」这一步要说清的三件事：为什么、点哪儿、点完会怎样 */
    fun installPermissionSteps(): List<String> = listOf(
        "系统规定：App 自己下载的更新包，必须由你亲手允许「安装未知应用」才能装。",
        "点「去允许」会直接跳到系统里本应用的那一页，把开关打开就行 —— 一次就够，以后更新不再问。",
        "授权后回到 App，下载会自动继续，不用再点一遍。",
    )
}
