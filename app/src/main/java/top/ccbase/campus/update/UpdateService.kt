package top.ccbase.campus.update

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * 「更新下载不许因为切后台而断」的那一层壳。
 *
 * ## 为什么必须有个前台服务
 *
 * 2026-10-20 用户：「更着更着切了后台就断掉了，又要重下，我的服务器太差更新得很慢，
 * 更新时一定不能断」。
 *
 * 上一版的下载住在 [UpdateGate] 的进程内线程里 —— **进程一进后台就等于把命交给系统**：
 *   · Android 12+ 会把缓存进程**冻住**（frozen）：socket 不再有数据，看到的就是"卡死"；
 *   · 国产 ROM（小米/OPPO/vivo）更直接，锁屏几分钟就把后台进程清掉，下载线程当场消失。
 * 现在是 29MB 的包（见 `res/font` 下那几款字体），在校园网/弱网下要下好几分钟 ——
 * 这个时长足够让上面两件事各发生一次。
 *
 * 所以下载期间挂一个 [android.app.Service] + `startForeground`：
 * 前台服务是**进程优先级最高的一档**，不会被冻结、也不在清理名单里；
 * 同时那条常驻通知就是「还在下」的凭证（用户能在状态栏看到进度）。
 *
 * ## 三条边界
 *
 * 1. **服务只管"活着"，不碰下载逻辑**。谁在下、下到哪儿，仍然是 [UpdateGate] 一个人的事
 *    （上一代静默更新就是两个任务各下一份、抢同一个 `.part` 才坏掉的）。
 * 2. **被杀之后能自己接上**：[START_STICKY] + [PartStore] 落盘的半包 —— 系统把服务拉起来时，
 *    这里会发现状态机是空的，于是让 [UpdateGate.resumeIfPending] 从断点接着下。
 * 3. **起不来也不算失败**：Android 12+ 不许后台随便起前台服务（会抛
 *    `ForegroundServiceStartNotAllowedException`）。这时退回进程内下载 ——
 *    还是能下，只是享受不到"切后台不断"的待遇，绝不因此把更新判死。
 */
class UpdateService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfNow()
            return START_NOT_STICKY
        }
        // 必须在 5 秒内进前台，否则系统直接 ANR 掉服务
        enterForeground()
        // 系统把服务拉起来（进程被杀过）时状态机是空的：从半包接着下。
        // 注意顺序：UpdateGate.start() 会先把状态置成 Working 再叫我们，
        // 所以"正常的开始下载"走到这里时状态不是 Idle，不会重复起任务。
        if (UpdateGate.state is UpdateGate.S.Idle) {
            Thread({
                runCatching { UpdateGate.resumeIfPending(applicationContext, fromService = true) }
                    .onFailure { Log.w(TAG, "服务起来后接着下失败", it) }
            }, "update-resume").start()
        }
        // STICKY：被系统清掉后它会把服务再拉起来一次 —— 那一次就是我们"接着下"的机会
        return START_STICKY
    }

    override fun onDestroy() {
        alive = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /** 进前台：那条常驻通知和进度通知是**同一条**（同一个 id），下完由 UpdateGate 收掉。 */
    private fun enterForeground() {
        val n = runCatching {
            UpdateNotify.downloadNotification(this, UpdateGate.targetVersionName(this), pct = -1)
        }.getOrNull() ?: UpdateNotify.downloadNotification(this, "", pct = -1)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        runCatching { ServiceCompat.startForeground(this, UpdateNotify.ID_PROGRESS, n, type) }
            .onFailure {
                // 通知被系统禁掉时个别 ROM 会在这里抛：服务已经在跑了就够了，不因为发不出通知而自杀
                Log.w(TAG, "进前台失败（通知权限/ROM 限制）", it)
                runCatching { startForeground(UpdateNotify.ID_PROGRESS, n) }
            }
    }

    private fun stopSelfNow() {
        runCatching { UpdateGate.onServiceStopped() }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "UpdateService"

        const val ACTION_STOP = "top.ccbase.campus.UPDATE_STOP"

        /** 只用来判断"服务还在不在"（自检/日志用），不持有它做别的事。 */
        @Volatile
        private var alive: WeakReference<UpdateService>? = null

        val running: Boolean get() = alive?.get() != null

        /**
         * 叫服务来保命。**不抛异常**：起不来（系统限制）就退回进程内下载，
         * 调用方（[UpdateGate]）不需要写 try/catch。
         */
        fun start(ctx: Context) {
            val intent = Intent(ctx, UpdateService::class.java)
            val ok = runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Log.w(TAG, "起不了前台下载服务，退回进程内下载（切后台可能被系统冻住）", it) }
                .isSuccess
            if (!ok && !canStartFromBackground(ctx)) {
                Log.w(TAG, "当前是后台且没有电池白名单，Android 12+ 不允许起前台服务")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, UpdateService::class.java)) }
                .onFailure { Log.w(TAG, "停服务失败", it) }
        }

        /**
         * 现在能不能从后台起前台服务。
         *
         * Android 12 起后台应用不许起前台服务，**唯一**在系统里说得清的豁免是
         * 「用户把本应用加进了电池优化白名单」——[AutoUpdate] 那条闹钟到点的静默下载正是靠它。
         * 没有豁免时那条路就只能退回进程内跑，能不能活下来看运气 —— 这一点不藏着，
         * 「我的」页的自动更新开关下面会照实写。
         */
        fun canStartFromBackground(ctx: Context): Boolean = runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)
    }
}
