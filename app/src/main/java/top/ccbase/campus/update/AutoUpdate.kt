package top.ccbase.campus.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import top.ccbase.campus.BuildConfig
import top.ccbase.campus.net.Net
import java.util.concurrent.Executors

/**
 * 后台静默更新。
 *
 * 2026-10-20 用户：「以后如果用户没有主动进行更新你就在后台静默更新（记得索要相关权限），
 * 后台静默更新时加一个持续五秒的弹窗，提示'发现新版本，已在后台静默更新'，
 * 用户需在我的里面打开自动更新新版本」。
 *
 * 三条边界先说清楚，别把这几条当成"没做完"：
 *   1. **装包不可能真静默**。Android 只允许用户手势拉起系统安装器（除非是设备管理/系统应用）。
 *      所以"静默"= 静默**检查 + 下载 + 校验**，装包仍然要点一下 —— 横幅/通知/"我的"页那行。
 *   2. 只在**用户打开过自动更新开关**之后才跑，默认关（用户明确要求"需在我的里面打开"）。
 *   3. 与手动更新共用一个下载入口 [UpdateGate]：同版本只下一份，手动点击把它转前台，
 *      不会出现两个任务抢同一个 .part 文件（那正是上一代静默更新被砍掉的原因）。
 */
object AutoUpdate {

    const val ACTION = "top.ccbase.campus.AUTO_UPDATE_TICK"
    private const val TAG = "AutoUpdate"
    private const val NAME = "campus_auto_update"
    private const val K_ON = "auto_update_on"
    private const val K_WIFI = "auto_update_wifi_only"
    private const val K_LAST = "auto_update_last_run"

    /** 静默检查的最小间隔：6 小时。太勤会耗电，也太容易和用户手动更新撞在一起。 */
    private const val MIN_GAP_MS = 6 * 60 * 60 * 1000L
    private const val ALARM_MS = 6 * 60 * 60 * 1000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "auto-update").apply { isDaemon = true }
    }

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(K_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()
        if (on) schedule(ctx) else cancelAlarm(ctx)
    }

    fun wifiOnly(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(K_WIFI, true)

    fun setWifiOnly(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(K_WIFI, on).apply()
    }

    private fun lastRun(ctx: Context): Long =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(K_LAST, 0L)

    private fun markRun(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putLong(K_LAST, System.currentTimeMillis()).apply()
    }

    private fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 调度

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1001,
        Intent(ctx, AutoUpdateReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 一次性闹钟 + 每次跑完自己续期：比 setInexactRepeating 更容易穿过 Doze。 */
    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + ALARM_MS
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }.onFailure { Log.w(TAG, "排不上静默更新闹钟", it) }
    }

    fun cancelAlarm(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx))
        }
    }

    /** 进 App 时的兜底触发：闹钟在国产 ROM 上不一定准，打开 App 是唯一稳定时机。 */
    fun kickIfDue(ctx: Context) {
        if (!enabled(ctx)) return
        val gap = System.currentTimeMillis() - lastRun(ctx)
        if (gap < MIN_GAP_MS) return
        run(ctx, reason = "app-launch")
    }

    /**
     * 跑一次静默检查（必要时下载）。
     *
     * @param force 忽略 6 小时间隔（调试/闹钟触发用）
     */
    fun run(ctx: Context, reason: String = "alarm", force: Boolean = true) {
        if (!enabled(ctx)) return
        if (UpdateGate.state is UpdateGate.S.Working) {
            // 已经在下载了（不管是静默还是手动起的）：不掺和，绝不能开第二个
            Log.i(TAG, "跳过静默检查：已有下载任务在跑")
            return
        }
        if (wifiOnly(ctx) && !onWifi(ctx)) {
            Log.i(TAG, "跳过静默检查：只允许 Wi-Fi，当前不是 Wi-Fi")
            return
        }
        io.execute {
            try {
                val installed = runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
                }.getOrDefault(0)
                val outcome = Updater.check(
                    BuildConfig.UPDATE_BASE, Net.dial, installed,
                    UpdatePrefs.skipped(ctx), manual = false,
                )
                UpdatePrefs.markChecked(ctx)
                UpdatePrefs.markResult(ctx, when (outcome) {
                    is CheckOutcome.Update -> "静默检查（$reason）：发现 ${outcome.manifest.versionName}"
                    CheckOutcome.Latest -> "静默检查（$reason）：已是最新"
                    is CheckOutcome.Failed -> "静默检查（$reason）失败：${outcome.message}"
                })
                markRun(ctx)
                val m = (outcome as? CheckOutcome.Update)?.manifest ?: run {
                    schedule(ctx); return@execute
                }
                UpdatePrefs.rememberAvailable(ctx, m.versionCode, m.versionName)
                if (!Updater.canInstall(ctx)) {
                    // 没有"安装未知应用"权限就一个字节都不下 —— 下了也装不上，白耗流量
                    UpdateNotify.post(ctx, m, BuildConfig.VERSION_NAME)
                    schedule(ctx)
                    return@execute
                }
                when (UpdateGate.start(ctx, m, quiet = true)) {
                    UpdateGate.Start.STARTED -> Log.i(TAG, "静默下载已开始：${m.versionName}")
                    // 手动更新已经在下载同一版：让它当前台任务跑，静默这边什么都不做
                    UpdateGate.Start.ATTACHED -> Log.i(TAG, "手动更新已接管 ${m.versionName}")
                    UpdateGate.Start.READY_ALREADY -> Log.i(TAG, "更新包已就绪：${m.versionName}")
                    UpdateGate.Start.BUSY_OTHER -> Log.i(TAG, "别的下载在跑，静默放弃这一次")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "静默检查异常", t)
            } finally {
                schedule(ctx)
            }
        }
    }
}

/** 闹钟到点 / 开机后的静默检查（goAsync 保证进程存活到检查结束）。 */
class AutoUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AutoUpdate.enabled(context)) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                AutoUpdate.run(app, reason = intent.action?.substringAfterLast('.') ?: "alarm")
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
