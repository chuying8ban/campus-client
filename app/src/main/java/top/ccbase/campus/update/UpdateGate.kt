package top.ccbase.campus.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新下载的**唯一入口**。
 *
 * 2026-10-20 用户要求把"后台静默更新"加回来，但给了硬条件：
 * 「重点是两者不要冲突导致更新失败」。
 *
 * 上一次（3.4.40 之前）静默更新被砍掉，原因就是这个：静默任务和手动任务各下一份，
 * 同一个 `.part` 文件被两个线程同时写，包校验必然失败，用户看到的是"更新怎么都装不上"。
 *
 * 所以这里把"谁在下载"收成一个进程内单例：
 *   · 任何时候**只有一个**下载线程（单线程池）+ 一个版本号；
 *   · 手动点更新时，如果同版本已在后台下 → **接管**（quiet 置 false，把进度交到前台），
 *     不再发起第二次下载；
 *   · 如果后台已经下好 → 直接返回 READY，手动那边立刻弹"安装"；
 *   · 版本号不同（用户手动查到了新版本）→ 先取消旧任务，再开新的。
 *
 * 状态落 SharedPreferences：进程被系统杀掉后，"已经下好但还没装"这件事不能丢，
 * 否则用户第二天打开 App 又看不到它了。
 */
object UpdateGate {

    enum class Start { STARTED, ATTACHED, READY_ALREADY, BUSY_OTHER }

    sealed class S {
        data object Idle : S()

        /** 正在下载。quiet=true 表示后台静默下的，界面不该弹浮层。 */
        data class Working(
            val m: UpdateManifest,
            val quiet: Boolean,
            val fraction: Float,
            val note: String? = null,
        ) : S()

        /** 下好了，等用户点安装（安装器必须由用户手势拉起，系统不给后台静默装）。 */
        data class Ready(
            val m: UpdateManifest,
            val file: File,
            val quiet: Boolean,
        ) : S()

        data class Failed(val m: UpdateManifest, val msg: String, val quiet: Boolean) : S()
    }

    private const val NAME = "campus_update_gate"
    private const val K_CODE = "pending_code"
    private const val K_NAME = "pending_name"
    private const val K_PATH = "pending_path"
    private const val K_QUIET = "pending_quiet"

    // 正在下载的**目标**（不是"下好了待装"，是"下到一半"）。
    // 落盘的理由和半包一样：进程被系统杀掉/手机重启之后，"我在下哪个版本、从哪儿下、
    // 校验和是多少"不能跟着内存一起消失 —— 否则回来只能重新查一遍清单，而弱网下
    // 那一次清单请求恰好失败，用户看到的就是「更新自己消失了」。
    private const val K_T_CODE = "target_code"
    private const val K_T_NAME = "target_name"
    private const val K_T_URL = "target_url"
    private const val K_T_SHA = "target_sha"
    private const val K_T_SIZE = "target_size"
    private const val K_T_QUIET = "target_quiet"

    /** 静默下载失败后多久再试一次。别再等 6 小时 —— 那等于"今天别更新了"。 */
    private const val RETRY_AFTER_FAIL_MS = 30 * 60 * 1000L

    private const val TAG_RESUME = "UpdateGate"

    private val main = Handler(Looper.getMainLooper())
    // daemon：进程/测试 JVM 退出时不该被这个线程挂住（Robolectric 全量跑会卡在 shutdown）
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-gate").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(S) -> Unit>()
    private val lock = Any()
    private val busy = AtomicBoolean(false)

    /** 测试用：允许注入一个假的下载器（默认走真网络）。 */
    @Volatile
    var downloader: (Context, UpdateManifest, (Float) -> Unit) -> Result<File> =
        { c, m, p -> Updater.download(c, m, onProgress = p) }

    /**
     * 下载期间的"保命"开关（真机 = 起/停 [UpdateService] 前台服务）。
     *
     * 抽成可注入的，是为了让单测能在 JVM 上断言"下载一开始就叫了保命、结束就收掉"，
     * 而不用去驱动一个真的 Service。
     */
    @Volatile
    var keepAlive: (Context, Boolean) -> Unit = { c, on ->
        if (on) UpdateService.start(c) else UpdateService.stop(c)
    }

    /** 只给单测用：假装"装着的是哪一版"（真机上永远走 PackageManager）。 */
    @androidx.annotation.VisibleForTesting
    @Volatile
    var installedCodeOverride: Int? = null

    @Volatile
    var state: S = S.Idle
        private set

    fun observe(l: (S) -> Unit): () -> Unit {
        listeners += l
        main.post { l(state) }          // 立刻给一次当前值，别让界面等下一次事件
        return { listeners -= l }
    }

    private fun set(s: S) {
        state = s
        main.post { listeners.forEach { runCatching { it(s) } } }
    }

    /** 进程重启后把"下好待装"恢复出来。 */
    fun restore(ctx: Context) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val code = p.getInt(K_CODE, 0)
        if (code <= 0) return
        val f = p.getString(K_PATH, null)?.let { File(it) }
        if (f == null || !f.isFile || f.length() == 0L) {
            p.edit().clear().apply()
            return
        }
        if (state is S.Idle) {
            val name = p.getString(K_NAME, "") ?: ""
            set(S.Ready(UpdateManifest(versionCode = code, versionName = name), f, p.getBoolean(K_QUIET, true)))
        }
    }

    /** 有没有"已经下好、等他点安装"的版本（「我的」页那行用）。 */
    fun pending(ctx: Context): S.Ready? = state as? S.Ready

    /**
     * 唯一入口。
     *
     * @param quiet true = 后台静默下载（成功只留通知/横幅，不弹浮层）
     */
    fun start(
        ctx: Context,
        m: UpdateManifest,
        quiet: Boolean,
        onProgress: (Float) -> Unit = {},
    ): Start = synchronized(lock) {
        val now = state
        if (now is S.Working) {
            if (now.m.versionCode == m.versionCode) {
                // 同版本已在跑：手动点击 = 把它从后台拽到前台，绝不另开一条下载
                if (!quiet && now.quiet) set(now.copy(quiet = false))
                return@synchronized Start.ATTACHED
            }
            // 另一个版本正在下：**不打断它**（中途撕掉会留下半包，下一个任务还得清），
            // 直接把"忙"告诉调用方，让它提示用户稍后再试
            return@synchronized Start.BUSY_OTHER
        }
        if (now is S.Ready && now.m.versionCode == m.versionCode && now.file.isFile) {
            if (!quiet && now.quiet) set(now.copy(quiet = false))
            return@synchronized Start.READY_ALREADY
        }
        if (now is S.Ready && now.m.versionCode != m.versionCode) {
            // 待装的是旧版本：文件留着没用（桌面只能装一个），清掉省空间
            runCatching { now.file.delete() }
            clearPending(ctx)
            set(S.Idle)
        }
        if (!busy.compareAndSet(false, true)) return@synchronized Start.BUSY_OTHER

        set(S.Working(m, quiet, -1f))
        // 目标落盘 + 起前台服务，**必须在开线程之前**：
        // 这两件事是"切后台不断"的全部依据，晚一步就可能赶不上用户按下 Home 键。
        runCatching { saveTarget(ctx, m, quiet) }
        runCatching { keepAlive(ctx, true) }
        worker.execute {
            try {
                var lastPct = -2
                val r = downloader(ctx, m) { f ->
                    val pct = if (f < 0f) -1 else (f * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        val s = state
                        if (s is S.Working && s.m.versionCode == m.versionCode) {
                            set(s.copy(fraction = f))
                        }
                        if (!quiet) onProgress(f)
                        // 进度通知**不分静默/手动**：静默下载现在挂着前台服务，
                        // 系统要求前台服务必须有常驻通知；那条通知就是这条进度条。
                        UpdateNotify.progress(ctx, m.versionName, pct)
                    }
                }
                r.fold(
                    onSuccess = { f ->
                        runCatching { keepAlive(ctx, false) }
                        UpdateNotify.clearProgress(ctx)
                        runCatching { clearTarget(ctx) }
                        savePending(ctx, m, f, quiet)
                        if (quiet) runCatching { UpdateNotify.postReady(ctx, m) }   // 通知权限没开也别把下载判失败
                        set(S.Ready(m, f, quiet))
                    },
                    onFailure = { e ->
                        runCatching { keepAlive(ctx, false) }
                        UpdateNotify.clearProgress(ctx)
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: "下载没成功，请重试"
                        // 半包和目标都留着：下一次（开 App / 30 分钟后那条闹钟）从断点接着下
                        if (quiet) runCatching { AutoUpdate.scheduleIn(ctx, RETRY_AFTER_FAIL_MS) }
                        set(S.Failed(m, msg, quiet))
                    },
                )
            } finally {
                busy.set(false)
            }
        }
        Start.STARTED
    }

    /** 静默任务转前台（用户主动点了更新）。只改标记，不重启下载。 */
    fun promote() {
        val s = state
        if (s is S.Working && s.quiet) set(s.copy(quiet = false))
        if (s is S.Ready && s.quiet) set(s.copy(quiet = false))
    }

    fun cancel() {
        // 引擎的断点续传按 .part 记账；这里只把状态收掉，不要删半包 ——
        // 手动重试正是靠它接着下（删了就等于每次都从 0 开始）
        if (state is S.Working) set(S.Idle)
    }

    /** 只给单测用：把状态机复位（真机上没有这个入口）。 */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        installedCodeOverride = null
        set(S.Idle)
    }

    fun markInstalled(ctx: Context) {
        clearPending(ctx)
        clearTarget(ctx)
        set(S.Idle)
    }

    fun clearFailure() {
        if (state is S.Failed) set(S.Idle)
    }

    // ------------------------------------------------------------ 断点续跑

    /**
     * 进程被系统杀掉 / 手机重启 / 前台服务被拉起来之后：**从半包接着下**。
     *
     * 这是「切了后台就断、又要重下」的另一半答案（另一半是 [UpdateService] 的保命）：
     * 就算真的被杀，回来也不该从 0 开始 —— `filesDir/update` 下的 `.apk.part` 和它的元数据
     * 都在磁盘上，[DownloadEngine] 本来就会从半包末尾接着要下一段。
     *
     * 什么情况下**不**接着下（都要说得出理由，别偷偷放过）：
     *   · 没有目标 / 没有半包：没东西可续；
     *   · 版本已经装上（比装着的老）：目标作废；
     *   · 半包校验不过：引擎会自己清掉重下，不属于这里判；
     *   · 静默任务 + 只允许 Wi-Fi + 当前是流量：32MB 的包不该偷偷用流量，
     *     留到有 Wi-Fi 时（下一条闹钟/下次开 App）再续。
     *
     * @return true = 真的排上了一次续传
     */
    fun resumeIfPending(ctx: Context, fromService: Boolean = false): Boolean {
        if (state is S.Working || state is S.Ready) return false
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val code = p.getInt(K_T_CODE, 0)
        if (code <= 0) return false
        val m = UpdateManifest(
            versionCode = code,
            versionName = p.getString(K_T_NAME, "").orEmpty(),
            url = p.getString(K_T_URL, "").orEmpty(),
            sha256 = p.getString(K_T_SHA, "").orEmpty(),
            size = p.getLong(K_T_SIZE, 0L),
        )
        if (m.url.isBlank() || m.sha256.length != 64) {
            clearTarget(ctx)
            return false
        }
        val installed = installedCodeOverride ?: runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)
        if (code <= installed) {
            clearTarget(ctx)
            runCatching { Updater.partFile(ctx, m).delete() }
            return false
        }
        val part = Updater.partFile(ctx, m)
        if (!part.isFile || part.length() <= 0L) {
            // 半包没了：没东西可续，目标也一并清掉
            clearTarget(ctx)
            return false
        }
        val quiet = p.getBoolean(K_T_QUIET, true)
        if (quiet && AutoUpdate.wifiOnly(ctx) && !AutoUpdate.onWifi(ctx)) {
            android.util.Log.i(TAG_RESUME, "先不续：只允许 Wi-Fi，当前不是 Wi-Fi（半包留着，已下 ${part.length()} 字节）")
            return false
        }
        android.util.Log.i(
            TAG_RESUME,
            "从断点接着下：v${m.versionName}，已有 ${part.length()} / ${m.size} 字节（来源 ${if (fromService) "服务重启" else "进程启动"}）",
        )
        val r = start(ctx, m, quiet)
        return r == Start.STARTED || r == Start.ATTACHED || r == Start.READY_ALREADY
    }

    /** 前台服务自己收掉时调一下：别把 [S.Working] 当"有人在下"，那会让续传永远不触发。 */
    fun onServiceStopped() {
        if (state is S.Working && !busy.get()) set(S.Idle)
    }

    /** 前台服务重启时用来把标题写对（拿不到版本名就别硬编一个）。 */
    fun targetVersionName(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_T_NAME, "").orEmpty()

    private fun saveTarget(ctx: Context, m: UpdateManifest, quiet: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(K_T_CODE, m.versionCode)
            .putString(K_T_NAME, m.versionName)
            .putString(K_T_URL, m.url)
            .putString(K_T_SHA, m.sha256)
            .putLong(K_T_SIZE, m.size)
            .putBoolean(K_T_QUIET, quiet)
            .apply()
    }

    private fun clearTarget(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .remove(K_T_CODE).remove(K_T_NAME).remove(K_T_URL)
            .remove(K_T_SHA).remove(K_T_SIZE).remove(K_T_QUIET)
            .apply()
    }

    private fun savePending(ctx: Context, m: UpdateManifest, f: File, quiet: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(K_CODE, m.versionCode)
            .putString(K_NAME, m.versionName)
            .putString(K_PATH, f.absolutePath)
            .putBoolean(K_QUIET, quiet)
            .apply()
    }

    private fun clearPending(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
