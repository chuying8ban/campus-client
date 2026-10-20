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
                        if (!quiet) {
                            onProgress(f)
                            UpdateNotify.progress(ctx, m.versionName, pct)   // 状态栏常驻进度
                        }
                    }
                }
                r.fold(
                    onSuccess = { f ->
                        if (!quiet) UpdateNotify.clearProgress(ctx)
                        savePending(ctx, m, f, quiet)
                        if (quiet) runCatching { UpdateNotify.postReady(ctx, m) }   // 通知权限没开也别把下载判失败
                        set(S.Ready(m, f, quiet))
                    },
                    onFailure = { e ->
                        UpdateNotify.clearProgress(ctx)
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: "下载没成功，请重试"
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
        set(S.Idle)
    }

    fun markInstalled(ctx: Context) {
        clearPending(ctx)
        set(S.Idle)
    }

    fun clearFailure() {
        if (state is S.Failed) set(S.Idle)
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
