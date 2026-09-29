package top.ccbase.campus.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.ccbase.campus.alarm.AlarmScheduler
import top.ccbase.campus.alarm.NudgePrefs
import top.ccbase.campus.alarm.Notify
import top.ccbase.campus.alarm.Rescheduler
import top.ccbase.campus.alarm.Silence
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits

/** 从 Compose 的 Context 里挖出 Activity（申请通知权限要用） */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 「课前提醒」开关条 —— 在「我的」页最底下。
 *
 * 按用户要求压到最淡：**没有卡片底色，只有一根细上边线**。
 *
 * 两个开关**分开**（2026-09-19 用户要求"提醒要从 App 里来、别人也能用"）：
 *  * 课前提醒：只发一条本地通知。不碰系统状态、不需要任何特殊权限 ——
 *    任何用户装上就能用，所以默认开。
 *  * 顺便自动静音：要改系统铃声（要「修改系统设置」），影响的是系统状态，
 *    必须用户主动开，默认关。
 *
 * 权限状态是**实时显示**的（每 2 秒刷新）：这个功能我自己没法测
 * （本机无模拟器、也改不了真实手机的铃声），所以让界面自己成为诊断面板 ——
 * 缺哪个权限、下一个闹钟排在什么时候，一眼就能看到，
 * 出问题时你不用描述，截图就够。
 */
@Composable
fun NudgeStrip() {
    val ctx = LocalContext.current
    var remind by remember { mutableStateOf(NudgePrefs.remindEnabled(ctx)) }
    var silence by remember { mutableStateOf(NudgePrefs.silenceEnabled(ctx)) }
    var lead by remember { mutableIntStateOf(NudgePrefs.leadMinutes(ctx)) }
    var canWrite by remember { mutableStateOf(Silence.canWrite(ctx)) }
    var canExact by remember { mutableStateOf(Silence.canExactAlarm(ctx)) }
    var notifOk by remember { mutableStateOf(Notify.allowed(ctx)) }
    var nextText by remember { mutableStateOf(AlarmScheduler.ourNextAlarmText(ctx)) }
    var count by remember { mutableIntStateOf(AlarmScheduler.scheduledCount(ctx)) }

    // 进 App 时先按当前设置重排一次（系统可能清过闹钟、设置也可能被改过）
    LaunchedEffect(Unit) { Rescheduler.request(ctx, reason = "app-open") }

    // 每 2 秒刷新一次状态：从系统设置页返回后，界面会自己变对，不需要用户手动刷新
    LaunchedEffect(Unit) {
        while (true) {
            canWrite = Silence.canWrite(ctx)
            canExact = Silence.canExactAlarm(ctx)
            notifOk = Notify.allowed(ctx)
            nextText = AlarmScheduler.ourNextAlarmText(ctx)
            count = AlarmScheduler.scheduledCount(ctx)
            delay(2000)
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val anyOn = remind || silence

    Column(Modifier.fillMaxWidth()) {
        // ---------------------------------------------------------- 课前提醒
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("课前提醒", color = C.txt, fontSize = 14.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "上课/自习前 $lead 分钟弹一条通知 · 本机发出，不经任何服务器",
                    color = C.txt3, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }
            Switch(
                checked = remind,
                onCheckedChange = { want ->
                    if (want && Build.VERSION.SDK_INT >= 33 && !notifOk) {
                        // 没通知权限再开也是白开：先把系统授权框弹出来
                        notifPerm.launch("android.permission.POST_NOTIFICATIONS")
                    }
                    remind = want
                    NudgePrefs.setRemindEnabled(ctx, want)
                    Rescheduler.request(ctx, reason = if (want) "remind-on" else "remind-off")
                },
                colors = switchColors(C.violet),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 顺便静音
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动静音", color = C.txt, fontSize = 14.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "上课时把铃声切静音、下课后自动恢复（要系统权限）",
                    color = C.txt3, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }
            Switch(
                checked = silence,
                onCheckedChange = { want ->
                    silence = want
                    NudgePrefs.setSilenceEnabled(ctx, want)
                    Rescheduler.request(ctx, reason = if (want) "silence-on" else "silence-off")
                },
                colors = switchColors(C.violet),
            )
        }

        if (anyOn) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提前", color = C.txt3, fontSize = 11.5.sp)
                Spacer(Modifier.width(10.dp))
                listOf(0, 1, 2, 3, 5).forEach { m ->
                    val sel = m == lead
                    Box(
                        Modifier
                            .padding(end = 7.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) C.violet.copy(alpha = 0.18f) else C.card)
                            .clickable {
                                lead = m
                                NudgePrefs.setLeadMinutes(ctx, m)
                                Rescheduler.request(ctx, reason = "lead=$m")
                            }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    ) {
                        Text(
                            if (m == 0) "准点" else "$m 分",
                            color = if (sel) C.violet else C.txt2,
                            fontSize = 11.5.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            style = Digits,
                        )
                    }
                }
            }
        }

        if (anyOn) {
            Spacer(Modifier.height(16.dp))

            // 权限缺口：只列当前开关真正需要的那几项，别把用不上的也摆出来
            if (Build.VERSION.SDK_INT >= 33 && !notifOk) {
                PermRow("需要「通知」权限", "去授权") {
                    notifPerm.launch("android.permission.POST_NOTIFICATIONS")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (silence && !canWrite) {
                PermRow("需要「修改系统设置」权限（静音要用）", "去授权") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!canExact && Build.VERSION.SDK_INT >= 31) {
                PermRow("需要「闹钟与提醒」权限（否则到点不响）", "去授权") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下一个闹钟（我们排的）", color = C.txt3, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (count == 0) "未排上（检查上面的权限）" else "$nextText · 共 $count 个",
                    color = if (count == 0) C.amber else C.cyan,
                    fontSize = 11.5.sp, style = Digits,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "开启后状态栏会出现闹钟图标，这是让它不被系统杀掉的代价。",
                color = C.txt3, fontSize = 10.5.sp, lineHeight = 15.sp,
            )

            Spacer(Modifier.height(14.dp))
            // 自检面板：条件够不够 + 到底发生了没有（默认收起）
            SilenceDiagPanel()
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun switchColors(accent: androidx.compose.ui.graphics.Color) = SwitchDefaults.colors(
    checkedThumbColor = accent,
    checkedTrackColor = accent.copy(alpha = 0.35f),
    uncheckedThumbColor = C.txt3,
    uncheckedTrackColor = C.card,
    uncheckedBorderColor = C.lineHi,
)

@Composable
private fun PermRow(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(C.amber, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(9.dp))
        Text(text, color = C.amber, fontSize = 11.5.sp)
        Spacer(Modifier.weight(1f))
        Text(action, color = C.amber, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onClick))
        Spacer(Modifier.width(2.dp))
        Text("›", color = C.amber, fontSize = 13.sp)
    }
}
