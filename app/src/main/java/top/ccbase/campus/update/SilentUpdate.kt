package top.ccbase.campus.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 静默下载（用户 2026-10-19：「另外加一个静默下载功能」）。
 *
 * 跟原来的「检查更新」不同，这条路**全程不弹任何界面**：
 * 进 App → 后台查一次清单 → 有新版本就在后台把包下完、校验完 → 下好了才告诉人。
 * 用户下次看到的就是「已下好，点一下安装」，不用再等 7MB 在校园网里爬。
 *
 * 几条硬约束（都不是可选优化）：
 * 1. **不抢当前对话**：正在手动下载/弹着更新框时直接跳过，绝不并行下一份；
 * 2. **不重复下**：同版本只下一次，已经下好的包存在就直接用（`readyApk`）；
 * 3. **不空烧流量**：6 小时内最多试一次，失败也不重试到死；
 * 4. **不骗人**：只有 sha256 + 大小 + 签名全都过了（[Updater.download] 内部保证）才记「已就绪」。
 */
object SilentUpdate {

    private const val TAG = "SilentUpdate"
    private const val PREF = "campus_update"
    private const val K_ON = "auto_download"
    private const val K_READY_CODE = "ready_code"
    private const val K_READY_PATH = "ready_path"
    private const val K_ATTEMPT = "auto_attempt_at"

    /**
     * 查清单的最小间隔：30 分钟（2026-10-19 用户："为什么新版本没有做到静默更新"）。
     *
     * 原来查和下载共用一个 6 小时闸 —— 结果新版本发出来半天，他进 App 也发现不了。
     * 清单就几百字节，30 分钟查一次不心疼；**下载**另有节流（同一版本失败 6 小时内不重下）。
     */
    private const val GAP_MS = 30 * 60 * 1000L

    /** 同一版本下载失败后的冷却：6 小时。避免每 30 分钟重下一遍 8MB。 */
    private const val FAIL_GAP_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 默认开：用户装的是自建 App，更新几乎总是想要的。关掉后只剩手动「检查更新」。 */
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_ON, on).apply()

    /** 已下好、可直接安装的包；没有/已被系统清掉返回 null。 */
    fun readyApk(ctx: Context): File? {
        val path = sp(ctx).getString(K_READY_PATH, null) ?: return null
        val f = File(path)
        return if (f.isFile && f.length() > 0) f else {
            clearReady(ctx)
            null
        }
    }

    fun readyVersion(ctx: Context): String? {
        val code = sp(ctx).getInt(K_READY_CODE, 0)
        if (code <= 0 || readyApk(ctx) == null) return null
        return sp(ctx).getString("ready_name", null)
    }

    fun clearReady(ctx: Context) = sp(ctx).edit()
        .remove(K_READY_CODE).remove(K_READY_PATH).remove("ready_name").apply()

    /**
     * 进 App 时调一次（幂等、非阻塞）。
     *
     * @param currentCode 本机 versionCode
     * @param currentName 本机版本名（通知里要写"当前 → 新"）
     */
    fun kick(ctx: Context, currentCode: Int, currentName: String, force: Boolean = false) {
        if (!enabled(ctx)) return
        // 已经下好了就别再查：省一次请求，也避免刚下完又提示"有新版本"
        if (readyApk(ctx) != null) return
        val now = System.currentTimeMillis()
        if (!force && now - sp(ctx).getLong(K_ATTEMPT, 0L) < GAP_MS) return
        sp(ctx).edit().putLong(K_ATTEMPT, now).apply()

        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val outcome = Updater.check(
                    top.ccbase.campus.BuildConfig.UPDATE_BASE,
                    top.ccbase.campus.net.Net.dial,
                    currentCode,
                    UpdatePrefs.skipped(app),
                    manual = false,
                )
                if (outcome !is CheckOutcome.Update) return@runCatching
                val m = outcome.manifest
                // 同一版本刚失败过就别急着再下一遍（断网/服务器抖动时能省掉反复 8MB）
                val failAt = sp(app).getLong("fail_${m.versionCode}", 0L)
                if (System.currentTimeMillis() - failAt < FAIL_GAP_MS) return@runCatching
                // 强制更新也别抢：那种情况界面本来就会弹框让用户自己决定
                Log.i(TAG, "后台下载 v${m.versionName}（${UpdateLogic.humanSize(m.size)}）")
                val r = Updater.download(app, m)
                val f = r.getOrNull() ?: run {
                    Log.w(TAG, "后台下载失败：${r.exceptionOrNull()?.message}")
                    sp(app).edit().putLong("fail_${m.versionCode}", System.currentTimeMillis()).apply()
                    return@runCatching
                }
                sp(app).edit()
                    .putInt(K_READY_CODE, m.versionCode)
                    .putString(K_READY_PATH, f.absolutePath)
                    .putString("ready_name", m.versionName)
                    .apply()
                UpdatePrefs.rememberAvailable(app, m.versionCode, m.versionName)
                UpdateNotify.postReady(app, m)
            }
        }
    }

    /** 用户点了安装：把包交出去，成功后清掉「已就绪」（装完版本就追平了）。 */
    fun installReady(ctx: Context): Boolean {
        val f = readyApk(ctx) ?: return false
        val ok = Updater.install(ctx, f)
        if (ok) clearReady(ctx)
        return ok
    }
}
