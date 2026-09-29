package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.Api
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.Net
import top.ccbase.campus.net.Transport

/**
 * 「手机直接提醒」：定时问一次服务端有没有新的余位提醒，有就**本机弹通知**。
 *
 * 和原来那套推送到飞书/QQ 的区别就在"谁发这个通知"：
 * 以前是服务端推给作者个人的机器人（别人装 App 收不到），
 * 现在是每台手机自己问、自己弹 —— 谁登录谁收。
 *
 * 排程刻意**不常驻**：App 不需要一直活着，闹钟到点由系统唤醒（
 * `setAndAllowWhileIdle` 在打盹模式下也能唤醒一次，间隔 10 分钟）。
 * 每次跑完自己排下一次，所以用户在"设置 → 应用 → 电池"里限制后台也不至于彻底断掉。
 */
object GrabWatch {

    const val ACTION = "top.ccbase.campus.GRAB_WATCH"

    /** 10 分钟一轮：抢课窗口里够快，又不至于把电量当水用 */
    const val INTERVAL_MS = 10 * 60 * 1000L

    /** 状态落在 SharedPreferences 里（用户常在户外看不到界面，诊断只能靠它） */
    private const val PREF = "grab_watch"
    private const val K_ON = "on"
    private const val K_LAST_ID = "last_seen_id"
    private const val K_LAST_RUN = "last_run_ms"
    private const val K_LAST_TXT = "last_result"

    /** 从没跑过：第一次只记账不弹通知，避免把历史提醒全轰一遍 */
    const val NEVER = -1

    private const val REQ = 9101
    private const val TAG = "GrabWatch"

    /** 测试注入：真机走 [RealTransport] */
    var transportOverride: Transport? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ---------------------------------------------------------------- 开关

    fun isOn(ctx: Context): Boolean = sp(ctx).getBoolean(K_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_ON, on).apply()
        if (on) {
            // 打开时把进度清成"没跑过"：下一次只记账，用户不会立刻被旧提醒轰一遍
            setLastSeenId(ctx, NEVER)
            schedule(ctx)
        } else {
            cancel(ctx)
        }
    }

    // ---------------------------------------------------------------- 账本

    fun lastSeenId(ctx: Context): Int = sp(ctx).getInt(K_LAST_ID, NEVER)

    fun setLastSeenId(ctx: Context, id: Int) {
        sp(ctx).edit().putInt(K_LAST_ID, id).apply()
    }

    fun lastRunMs(ctx: Context): Long = sp(ctx).getLong(K_LAST_RUN, 0L)

    fun lastResult(ctx: Context): String? = sp(ctx).getString(K_LAST_TXT, null)

    /** 落一条人话诊断；返回同一句，方便调用方直接断言 */
    fun note(ctx: Context, text: String): String {
        sp(ctx).edit().putLong(K_LAST_RUN, System.currentTimeMillis())
            .putString(K_LAST_TXT, text).apply()
        Log.i(TAG, text)
        return text
    }

    // ---------------------------------------------------------------- 排程

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQ,
        Intent(ctx, GrabWatchReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context) {
        if (!isOn(ctx)) return
        arm(ctx, System.currentTimeMillis() + INTERVAL_MS)
    }

    private fun arm(ctx: Context, atMs: Long) {
        runCatching {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(ctx))
        }.onFailure { Log.w(TAG, "排下一次提醒失败（可能缺精确闹钟权限）", it) }
    }

    fun cancel(ctx: Context) {
        runCatching { am(ctx).cancel(pending(ctx)) }
    }

    // ---------------------------------------------------------------- 跑一轮

    /**
     * 查一次并弹提醒。返回一句人话结果（同时写进诊断）。
     * 接收器和新版界面都调这里，保证"手动查"和"后台查"行为一致。
     */
    fun runOnce(ctx: Context, api: CampusApi? = null, nowMs: Long = System.currentTimeMillis()): String {
        val appCtx = ctx.applicationContext
        if (!isOn(appCtx)) return "未开启"
        val token = TokenStore.token(appCtx) ?: return note(appCtx, "还没登录，跳过这次检查")

        val st = try {
            runBlocking {
                (api ?: CampusApi(transport = transportOverride ?: Net.dial)).grabStatus(token)
            }
        } catch (e: Exception) {
            return note(appCtx, "网络不通，这轮没查到")
        }
        val status = when (st) {
            is ApiResult.Err -> return note(appCtx, "服务端说：${st.message}")
            is ApiResult.Ok -> st.value
        }

        val seen = lastSeenId(appCtx)
        val logs = status.logs
        val newest = maxOf(GrabWatchLogic.maxId(logs), if (seen == NEVER) 0 else seen)

        if (seen == NEVER) {
            setLastSeenId(appCtx, newest)
            arm(appCtx, nowMs + INTERVAL_MS)
            return note(appCtx, "首次运行：记住进度（#${newest}），不翻旧账")
        }

        val fresh = GrabWatchLogic.newAlerts(logs, seen)
        var popped = 0
        fresh.take(GrabWatchLogic.MAX_PER_RUN).forEach { if (GrabNotify.alert(appCtx, it)) popped++ }
        if (fresh.size > GrabWatchLogic.MAX_PER_RUN) {
            GrabNotify.summary(appCtx, fresh.size - GrabWatchLogic.MAX_PER_RUN, nowMs)
        }
        setLastSeenId(appCtx, newest)
        arm(appCtx, nowMs + INTERVAL_MS)
        return note(appCtx, "检查完成：新增 ${fresh.size} 条提醒，弹出 $popped 条")
    }
}
