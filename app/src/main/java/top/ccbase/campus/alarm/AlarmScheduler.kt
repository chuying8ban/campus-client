package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import top.ccbase.campus.domain.NudgeAction
import top.ccbase.campus.domain.NudgeKind
import java.time.LocalDateTime

/**
 * 把「提醒 / 静音动作」变成系统闹钟。
 *
 * 为什么必须交给 AlarmManager，而不是自己在 App 里挂个定时器：
 * 原生 App 的进程随时会被系统杀掉，挂定时器等于没有。
 * 交给系统后，**App 关掉、清后台、甚至没启动过，到点照样生效** ——
 * 这正是网页版做不到那件事的原因（网页必须有个活着的页面）。
 *
 * 用 setAlarmClock() 而不是 setExactAndAllowWhileIdle()：
 * 它是"闹钟级"优先级，最不容易被国产 ROM 的后台管控吞掉（HyperOS 尤其）。
 * 代价：状态栏会出现一个闹钟图标 —— 这是必须让用户知道的取舍。
 *
 * 排什么是 [top.ccbase.campus.domain.planActions] 决定的（纯逻辑，单测在那边）。
 * 这里只负责"把动作落到系统闹钟上"：id 分配、上限裁剪、取消、自检计数。
 */
object AlarmScheduler {

    const val ACTION_REMIND = "top.ccbase.campus.REMIND"
    const val ACTION_SILENCE = "top.ccbase.campus.SILENCE"
    const val ACTION_UNSILENCE = "top.ccbase.campus.UNSILENCE"

    private const val PREF = "alarm_ids"
    private const val BASE_ID = 1000
    private const val K_NEXT = "next_ms"

    /** 一次最多排多少个 —— 原生闹钟数量有上限，宁可少排、下次再续 */
    const val MAX_ALARMS = 60

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun actionName(kind: NudgeKind): String = when (kind) {
        NudgeKind.REMIND -> ACTION_REMIND
        NudgeKind.SILENCE -> ACTION_SILENCE
        NudgeKind.UNSILENCE -> ACTION_UNSILENCE
    }

    private fun op(ctx: Context, id: Int, action: String, title: String, body: String, atMs: Long, what: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, NudgeReceiver::class.java).apply {
                this.action = action
                putExtra("title", title)
                putExtra("body", body)
                // 原定触发时刻：真正弹的时候要用它算"还有几分钟"，晚响了不能还说"1 分钟后"
                putExtra("at", atMs)
                // 标题里的名词（上课 / 早自习 / 晚自习）—— 到点才知道该说哪句话
                putExtra("what", what)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 排好这批动作。返回实际排上的闹钟数。
     *
     * 每次都先 cancelAll：否则上次排的会残留下来，改周次/改提前量之后
     * 会出现"按旧设置提醒"的幽灵闹钟，而且极难查。
     */
    fun schedule(ctx: Context, actions: List<NudgeAction>): Int {
        cancelAll(ctx)
        var n = 0
        var idx = 0
        var earliest = 0L
        for (a in actions) {
            if (n >= MAX_ALARMS) break
            val ms = msOf(a.at)
            if (set(ctx, BASE_ID + idx, actionName(a.kind), a.at, ms, a.title, a.body, a.what)) {
                n++
                if (earliest == 0L || ms < earliest) earliest = ms
            }
            idx++
        }
        // 记下"我们自己排的下一个"。为什么不直接读系统的 nextAlarmClock：
        // 那个 API 返回的是**全系统**下一个闹钟（任何 App 排的都算），
        // 把它显示在"共 N 个"旁边，用户会读成"我们排的下一个是 0 点"——
        // 实测就发生过：系统的 09-17 00:00（别人的 0 点提醒）盖住了我们真正的 09-18 09:29。
        sp(ctx).edit().putLong(K_NEXT, earliest).apply()
        return n
    }

    private fun set(ctx: Context, id: Int, action: String, at: LocalDateTime, atMs: Long, title: String, body: String, what: String = ""): Boolean {
        if (atMs <= System.currentTimeMillis()) return false     // 过期的直接跳过
        return try {
            am(ctx).setAlarmClock(
                AlarmManager.AlarmClockInfo(atMs, showPending(ctx)),
                op(ctx, id, action, title, body, atMs, what),
            )
            sp(ctx).edit().putBoolean(id.toString(), true).apply()
            true
        } catch (_: SecurityException) {
            false   // 精确闹钟权限没给 —— 上层会提示用户去授权
        }
    }

    fun cancelAll(ctx: Context) {
        for (k in sp(ctx).all.keys) {
            val id = k.toIntOrNull() ?: continue
            am(ctx).cancel(op(ctx, id, ACTION_REMIND, "", "", 0L, ""))
            am(ctx).cancel(op(ctx, id, ACTION_SILENCE, "", "", 0L, ""))
            am(ctx).cancel(op(ctx, id, ACTION_UNSILENCE, "", "", 0L, ""))
        }
        sp(ctx).edit().clear().apply()
    }

    /** 我们自己排的下一个闹钟（界面显示"下一个闹钟"就该显示这个） */
    fun ourNextAlarmText(ctx: Context): String {
        val ms = sp(ctx).getLong(K_NEXT, 0L)
        if (ms <= 0L) return "无（还没排上）"
        if (ms <= System.currentTimeMillis()) return "已过（等下次重排）"
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        return String.format("%02d-%02d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    private fun msOf(at: LocalDateTime): Long =
        at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * **系统全局**的下一个闹钟（含其他 App 排的）—— 只用于自检对照，
     * 不要拿它当"我们排的下一个"显示给用户。
     */
    fun nextAlarmText(ctx: Context): String =
        am(ctx).nextAlarmClock?.triggerTime?.let {
            val t = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            String.format("%02d-%02d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
        } ?: "无"

    fun scheduledCount(ctx: Context): Int =
        sp(ctx).all.keys.count { it.toIntOrNull() != null }   // 别把 K_NEXT 数进闹钟个数

    private fun showPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, top.ccbase.campus.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * 开关与提前量。放 SharedPreferences 而不是数据库 ——
 * 这些是**闹钟排程的输入**，要在 App 没启动时（开机广播里）也能读到。
 *
 * 两个开关的区别（2026-09-19 拆开的）：
 *  * 提醒：只发一条本地通知，不碰任何系统状态 —— 任何用户装上就能用，所以默认**开**。
 *  * 静音：要「修改系统设置」权限改铃声音量/模式 —— 影响系统状态，必须用户主动开。
 */
object NudgePrefs {
    private const val PREF = "nudge"
    private const val K_REMIND = "remind_on"
    private const val K_SILENCE = "auto_silence_on"
    private const val K_LEAD = "lead_minutes"
    private const val K_NOTIF_ASKED = "notif_asked"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 课前提醒（通知）—— 默认开：不改系统状态，人人可用 */
    fun remindEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_REMIND, true)

    fun setRemindEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_REMIND, on).apply()

    /** 顺手自动静音 —— 默认关：涉及改系统铃声，必须用户主动开 */
    fun silenceEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_SILENCE, false)

    fun setSilenceEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_SILENCE, on).apply()

    /** 有任何一个开关是开的，就得往系统里排闹钟 */
    fun anyEnabled(ctx: Context): Boolean = remindEnabled(ctx) || silenceEnabled(ctx)

    fun leadMinutes(ctx: Context): Int = sp(ctx).getInt(K_LEAD, 1)

    fun setLeadMinutes(ctx: Context, m: Int) = sp(ctx).edit().putInt(K_LEAD, m.coerceIn(0, 30)).apply()

    /**
     * 通知权限有没有**问过一次**。
     * 只在第一次启动时问一次：反复弹系统授权框比不给提醒还烦。
     */
    fun notifAsked(ctx: Context): Boolean = sp(ctx).getBoolean(K_NOTIF_ASKED, false)

    fun setNotifAsked(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_NOTIF_ASKED, v).apply()
}
