package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动静音的**落盘记录**与**体检快照读取**。
 *
 * 记录落文件（不是只有 Logcat）：用户在户外没法目视、也没法连 logcat，
 * 出了问题要能从文件里读出来"排上了没有、响了没有、为什么没静音"。
 *
 * 核心逻辑只吃 [File]，方便在 JVM 测试里用临时目录真跑；Context 版本是薄包装。
 */
object DiagLog {

    /** 「模式访问权限」里本应用那一项的直接入口。用字面量而不是 Settings 常量：部分 ROM 没实现，
     *  而且常量在不同 compileSdk 下可见性不一样 —— 字符串最稳，失败由 intentsFor 的下一项兜。 */
    private const val NOTIFICATION_POLICY_ACCESS_DETAIL =
        "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

    private const val FILE_NAME = "silence_log.txt"
    private const val MAX_LINES = 500          // 只留最近若干条，别无限增长

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    @Synchronized
    fun append(dir: File, a: Attempt) {
        runCatching {
            dir.mkdirs()
            val f = File(dir, FILE_NAME)
            f.appendText(SilenceDiag.format(a) + "\n")
            trim(f)
        }
    }

    @Synchronized
    fun append(ctx: Context, a: Attempt) = append(ctx.filesDir, a)

    fun read(dir: File, limit: Int = 50): List<Attempt> {
        val f = File(dir, FILE_NAME)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines()
                .mapNotNull { SilenceDiag.parse(it) }
                .takeLast(limit)
                .reversed()          // 最近的在前，界面上第一眼看到最新一次
        }.getOrDefault(emptyList())
    }

    fun read(ctx: Context, limit: Int = 50): List<Attempt> = read(ctx.filesDir, limit)

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size <= MAX_LINES) return
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    // ------------------------------------------------------------- 体检（很薄的一层）

    /** 把系统真值读进来 —— 判定逻辑在 [SilenceDiag.items]，这里只做取值。 */
    fun snapshot(ctx: Context): DiagSnapshot {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return DiagSnapshot(
            writeSettings = runCatching { Settings.System.canWrite(ctx) }.getOrDefault(false),
            dndAccess = runCatching {
                nm?.isNotificationPolicyAccessGranted ?: false
            }.getOrDefault(false),
            exactAlarm = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am?.canScheduleExactAlarms() ?: false
                } else true     // 低版本没有这个限制
            }.getOrDefault(false),
            batteryWhitelist = runCatching {
                pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
            }.getOrDefault(false),
            notificationsOn = runCatching {
                nm?.areNotificationsEnabled() ?: false
            }.getOrDefault(false),
            // 装更新要系统允许本 App 安装应用；读不到就按「没授权」处理（宁可多提示一次）
            installAllowed = runCatching {
                ctx.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false),
        )
    }

    /** 系统视角的"下一个闹钟"。和我们自己排的清单对不上，就说明没排上。 */
    fun systemNextAlarm(ctx: Context): String? {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
        return runCatching {
            // AlarmClockInfo 只有触发时间与 showIntent（没有 label，标签在 PendingIntent 上）
            val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) am.nextAlarmClock else null
            c?.let { SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it.triggerTime)) }
        }.getOrNull()
    }

    /** 权限页跳转（体检项上的"去授权"按钮用）。 */
    fun intentFor(ctx: Context, item: DiagItem): android.content.Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (item.name) {
            "修改系统设置" -> android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            // 先试「直接落到本应用那一项」的详情页：小米上那页叫「模式访问权限」，
            // 一进去是一条几十项的列表，用户很容易翻不到「校园」（2026-09-17 用户截图实证）。
            // 这个 action 部分 ROM 没实现 → openItem() 会退到列表页。
            "勿扰访问" -> android.content.Intent(NOTIFICATION_POLICY_ACCESS_DETAIL)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            "闹钟与提醒" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            } else null
            // 直接弹「是否允许后台常驻」的系统窗 —— 比把用户丢进应用列表里自己找强得多。
            // 需要清单里的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS；被 ROM 拦下时 openItem() 会退到列表页。
            "电池白名单" -> android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "通知" -> android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            "安装更新" -> android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
            else -> null
        }
    }

    /**
     * 依次要试的意图（顺序就是优先级）。抽出来是为了能被单测钉住：
     * 「勿扰访问必须先试本应用详情页」这种事，只有断言顺序才守得住。
     */
    fun intentsFor(ctx: Context, item: DiagItem): List<android.content.Intent> =
        listOfNotNull(intentFor(ctx, item), fallbackFor(ctx, item))

    /** 首选页打不开时的备选（有些 ROM 砍掉了某个设置页，直接 startActivity 会抛）。 */
    fun fallbackFor(ctx: Context, item: DiagItem): android.content.Intent? = when (item.name) {
        "电池白名单" -> android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // 详情页没有的话，退到「应用列表」那一页（至少方向对），而不是直接掉到最外层的应用详情
        "勿扰访问" -> android.content.Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        else -> android.content.Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")
        )
    }

    /**
     * 打开某一项的授权页，三级兜底：首选页 → 备选页 → 应用详情页。
     * 返回 false 表示一个都没打开（界面据此给回退提示，而不是假装跳过去了）。
     */
    fun openItem(ctx: Context, item: DiagItem): Boolean {
        for (i in intentsFor(ctx, item)) {
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}
