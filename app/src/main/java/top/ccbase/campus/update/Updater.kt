package top.ccbase.campus.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import top.ccbase.campus.net.Transport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "Updater"

/**
 * APK 字节流下载。
 *
 * 为什么不复用 [Transport]：那个接口把响应读成 String，二进制包会被字符集转码毁掉。
 * 这里按块交给 sink，顺便报进度 —— 7MB 在校园网里要几十秒，没有进度条的更新框
 * 会被当成"卡死了"然后被划掉。
 */
fun interface ApkFetcher {
    fun fetch(url: String, sink: (ByteArray, Int, Long) -> Unit): Long
}

class RealApkFetcher(private val transport: Transport = top.ccbase.campus.net.Net.dial) : ApkFetcher {
    /**
     * 走**传输层**下载（即 App 里那条会自动选路的链路）。
     *
     * 教训（2026-09-17 真机）：这里以前自己 `URL(url).openConnection()`，于是自检和接口全绿、
     * 唯独更新失败 `Connection reset` —— 因为更新包绕开了"不带 SNI"的那条路。二进制下载
     * 必须和业务请求走同一条链路，否则这种"只有更新坏"的病会一再复发。
     */
    override fun fetch(url: String, sink: (ByteArray, Int, Long) -> Unit): Long = transport.stream(url, sink)
}

/**
 * 把老式的一次性下载器接到新的分片下载引擎上（给测试和任何还在用 [ApkFetcher] 的地方兜底）。
 *
 * 它**永远报 200** —— 因为它拿不到响应头，也就证明不了"服务端给的是同一个文件的第 N 段"。
 * 引擎收到 200 的处置是"清空半包、从 0 重下"，也就是"能下完，但每次断流都得重来"。
 * 真正的续传能力靠 [RealRangedFetcher]。
 */
fun ApkFetcher.asRanged(): RangedFetcher = RangedFetcher { url, _, _, decide ->
    // 老式下载器拿不到响应头 → 只能按「200 整包」上报（引擎会清空半包、从 0 写）
    val head = StreamHead(code = 200)
    val sink = decide(head)
    if (sink != null) fetch(url) { buf, n, _ -> sink(buf, n) }
    head
}

/** 真正的分片下载器：走传输层的 [Transport.streamRanged]，能拿到 206/ETag/Content-Range。 */
class RealRangedFetcher(private val transport: Transport = top.ccbase.campus.net.Net.dial) : RangedFetcher {
    override fun fetch(url: String, offset: Long, ifRange: String?, decide: (StreamHead) -> ByteSink?): StreamHead =
        transport.streamRanged(url, offset, ifRange, decide)
}

/** 一次检查的结果。UI 只需要这三种情况，不必知道底下怎么错的。 */
sealed class CheckOutcome {
    data class Update(val manifest: UpdateManifest, val forced: Boolean, val why: String) : CheckOutcome()
    data object Latest : CheckOutcome()
    data class Failed(val message: String) : CheckOutcome()
}

/**
 * 自动更新：查版本 → 下载 → 校验 → 拉起系统安装器。
 *
 * 我们走的是**旁加载**（没有应用商店），所以安全边界只有两条，两条都必须真的执行：
 *   1. `sha256` 对不上就整包丢弃（防半截下载 / 被中间人换包）；
 *   2. 下载包的**签名证书**必须和当前 App 一致 —— Android 本来也会拒绝签名不符的覆盖安装，
 *      提前查一遍是为了给出人话的原因，而不是让系统抛一句 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
 */
object Updater {

    const val DIR = "update"

    fun manifestUrl(base: String): String = UpdateLogic.manifestUrl(base)

    /**
     * 安装包落在 **filesDir**（不是 cacheDir）。
     *
     * cacheDir 里的一切都必须假设"系统随时会清掉"——而断点续传的半包和元数据正是
     * 要求"下次打开 App 还在"的东西，放 cacheDir 等于把续传能力交给系统心情。
     * 相应地 `res/xml/file_paths.xml` 里必须有 files-path（安装时靠它换成 content:// URI）。
     */
    fun destFile(ctx: Context, m: UpdateManifest): File =
        File(File(ctx.filesDir, DIR), "campus-${m.versionName.ifBlank { m.versionCode.toString() }}.apk")

    /** 半包：续传的载体（和元数据 `xxx.part.json` 放一起） */
    fun partFile(ctx: Context, m: UpdateManifest): File = File(destFile(ctx, m).path + ".part")

    // ---------------------------------------------------------------- 查

    /**
     * 查更新清单。
     *
     * 说清一件事：清单本身也会**断**（学生在移动网络上，第一次请求就 reset 很常见）。
     * 所以这里也退避重试 3 次（2s/5s/15s），而且每一种失败都翻成人话 ——
     * 「网络不通」「HTTP 500」「格式不对」是三件不同的事，用户和开发者要能分清。
     * 全程不抛异常给调用方（更新检查绝不能把 App 弄崩）。
     *
     * @param sleeper 退避等待。测试里换成空实现，不然一条用例要真的站 22 秒。
     */
    fun check(
        base: String,
        transport: Transport,
        currentCode: Int,
        skippedCode: Int = 0,
        /** 用户主动点的「检查更新」：不受「以后再说」影响 */
        manual: Boolean = false,
        sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    ): CheckOutcome {
        val url = manifestUrl(base)
        var lastWhy: String? = null
        var attempt = 0
        while (attempt < RetryPolicy.MAX_ATTEMPTS) {
            if (attempt > 0) sleeper(RetryPolicy.delayMs(attempt))
            attempt++
            val reply = try {
                transport.call("GET", "$url?t=${System.currentTimeMillis()}", emptyMap(), null)
            } catch (e: Exception) {
                Log.w(TAG, "取更新清单失败（第 $attempt 次）：$url", e)
                lastWhy = "网络不通，稍后再试"
                continue
            }
            // 404 = 这台服务器还没有更新通道（测试隧道就是这样），对用户来说等于"没有更新"
            if (reply.code == 404) return CheckOutcome.Latest
            if (reply.code !in 200..299) {
                Log.w(TAG, "更新清单 HTTP ${reply.code}（第 $attempt 次）：$url")
                // 4xx 是"这台服务器现在就是这个状态"：重试没有意义，直接说清
                if (reply.code in 400..499) return CheckOutcome.Failed("服务器返回 HTTP ${reply.code}")
                lastWhy = "服务器返回 HTTP ${reply.code}"
                continue
            }
            // 拿到了 200 但内容读不出来 → 重试也没用（同一个文件还是那份内容），直接报清楚
            val m = UpdateLogic.parse(reply.text)
                ?: return CheckOutcome.Failed("更新信息读不出来（格式不对）—— 请让管理员检查更新通道")
            return when (val d = UpdateLogic.decide(currentCode, m, skippedCode, manual)) {
                is UpdateLogic.Decision.UpToDate -> CheckOutcome.Latest
                is UpdateLogic.Decision.Optional -> CheckOutcome.Update(m, forced = false, why = "")
                is UpdateLogic.Decision.Forced -> CheckOutcome.Update(m, forced = true, why = d.why)
            }
        }
        return CheckOutcome.Failed(lastWhy ?: "网络不通，稍后再试")
    }

    // ---------------------------------------------------------------- 下

    /**
     * 下载并校验。
     *
     * 三条行为上的承诺（都是"绝不骗用户"）：
     *  1. **半包永远不安装**：只有大小 + sha256 都过关的字节才会被改名成安装包；
     *  2. 断流/超时/卡死 → 退避重试并**接着上次的字节**继续（半包和元数据留在磁盘上，
     *     跨 App 重启也能续）；失败时**保留**半包，点「重试」就从断的地方接着下；
     *  3. **全程一个 URL**：manifest 给的那个地址，中途绝不换域名/换镜像（换路续写 =
     *     把两个不同文件的字节拼起来，那是个装不上的坏包）。
     *
     * 续传判定、退避节奏、停滞检测全在 [DownloadEngine]（纯 JVM，好测）；这里只做
     * Android 侧的事：定地址、准备目录、把进度翻成界面要的两路回调、以及签名预检。
     *
     * @param onProgress 0..1；大小未知时回调 -1f
     * @param onState 更详细的进度（已下字节/总字节、第几次尝试、退避剩余毫秒）—— 界面靠它
     *   显示「网络断了，2 秒后自动重试（已下 3.2 MB）」，别让用户对着不动的进度条猜
     */
    fun download(
        ctx: Context,
        m: UpdateManifest,
        fetcher: RangedFetcher = RealRangedFetcher(),
        base: String = top.ccbase.campus.net.Api.BASE,
        onProgress: (Float) -> Unit = {},
        onState: (DownloadProgress) -> Unit = {},
        tuning: DownloadTuning = DownloadTuning(),
    ): Result<File> {
        val url = UpdateLogic.resolveUrl(base, m.url)
        val dest = destFile(ctx, m)
        val part = partFile(ctx, m)
        val dir = dest.parentFile ?: return Result.failure(DownloadFailed("找不到能写文件的目录"))
        dir.mkdirs()
        // 别的版本留下的半包再也续不上了，先清掉（每个 7MB，手机上看得出来）
        runCatching { PartStore(dir).clearOthers(keepName = part.name) }

        val engine = DownloadEngine(fetcher, tuning)
        val target = DownloadTarget(url = url, sha256 = m.sha256, size = m.size)
        var attempts = 1
        val r = engine.run(target, part, dest, onProgress = { st ->
            attempts = st.attempt
            onProgress(st.fraction)
            onState(st)
        })
        r.exceptionOrNull()?.let { Log.w(TAG, "下载失败：$url（尝试 $attempts 次）", it) }
        if (r.isFailure) return r

        val f = r.getOrThrow()
        // 校验和已经过了（在引擎里）；这里再核签名，是为了给出人话的原因，
        // 而不是让系统抛一句 INSTALL_FAILED_UPDATE_INCOMPATIBLE
        signerMismatch(ctx, f)?.let { why ->
            runCatching { f.delete() }
            return Result.failure(DownloadFailed("$why（已删除，请让管理员确认更新通道）"))
        }
        Log.i(TAG, "更新包就绪：${f.name} ${UpdateLogic.humanSize(f.length())}（尝试 $attempts 次）")
        return Result.success(f)
    }

    // ---------------------------------------------------------------- 装

    fun canInstall(ctx: Context): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 没权限就带去系统设置页，别只丢一句"请开启权限"让用户自己翻 */
    fun openInstallSettings(ctx: Context) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 拉起系统安装器（走 FileProvider，Android 7 起不许直接给 file:// URI） */
    fun install(ctx: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
        true
    }.getOrElse {
        Log.w(TAG, "拉起安装器失败", it)
        false
    }

    /**
     * 下载包的签名证书是否与本机 App 一致。
     * @return null = 一致；否则返回给人看的原因
     */
    private fun signerMismatch(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val mine = signerDigests(pm, ctx.packageName, archive = false)
        val theirs = signerDigests(pm, apk.absolutePath, archive = true)
        if (mine.isEmpty() || theirs.isEmpty()) return null   // 查不到就别拦，交给系统判定
        return if (mine.intersect(theirs).isEmpty())
            "这个安装包不是本应用签发的，已丢弃"
        else null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(pm: PackageManager, who: String, archive: Boolean): Set<String> {
        val info = runCatching {
            if (archive) pm.getPackageArchiveInfo(who, PackageManager.GET_SIGNING_CERTIFICATES)
            else pm.getPackageInfo(who, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: info.signingInfo?.signingCertificateHistory
        } else null
        val raw = sigs ?: info.signatures ?: return emptySet()
        return raw.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/** 检查节流 + 「以后再说」记忆。写在 SharedPreferences 里，重启也记得。 */
object UpdatePrefs {
    private const val NAME = "campus_update"
    private const val K_LAST = "last_check_ms"
    private const val K_SKIP = "skipped_version_code"
    private const val K_NOTIFIED = "notified_version_code"
    private const val K_AVAIL_CODE = "available_version_code"
    private const val K_AVAIL_NAME = "available_version_name"

    fun lastCheck(ctx: Context): Long =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(K_LAST, 0L)

    fun markChecked(ctx: Context, at: Long = System.currentTimeMillis()) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong(K_LAST, at).apply()
    }

    fun skipped(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(K_SKIP, 0)

    fun markSkipped(ctx: Context, code: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(K_SKIP, code).apply()
    }

    /** 已经为哪个版本发过通知栏提醒（同一个版本不重复打扰） */
    fun notified(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(K_NOTIFIED, 0)

    fun markNotified(ctx: Context, code: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(K_NOTIFIED, code).apply()
    }

    /** 已知可用的新版本：让「我的」页能长期显示有新版本可用（版本追平后自动不显示） */
    fun rememberAvailable(ctx: Context, code: Int, name: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(K_AVAIL_CODE, code).putString(K_AVAIL_NAME, name).apply()
    }

    fun availableCode(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(K_AVAIL_CODE, 0)

    fun availableName(ctx: Context): String? =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_AVAIL_NAME, null)
}
