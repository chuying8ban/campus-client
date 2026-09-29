package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import top.ccbase.campus.domain.NudgeKind
import top.ccbase.campus.domain.NudgeWindow
import top.ccbase.campus.domain.planActions
import top.ccbase.campus.domain.silenceNote
import java.time.LocalDateTime

/**
 * 「课前提醒」的**整条链路**验收（Robolectric）：排闹钟 → 到点被唤醒 →
 * 手机自己弹通知 → 落一条可读的记录。
 *
 * 为什么必须有这一层：用户要求"提醒从 App 里来，不要经第三方机器人"——
 * 那就是说这条链路得**自己证明能跑通**，而不是靠"单元函数算得对"糊过去。
 * 本机没有模拟器（不在 kvm 组），这是能做到的最接近真机的验证：
 * 用的是真的 AlarmManager 影子、真的 PendingIntent、真的 NudgeReceiver、真的通知服务。
 *
 * 卡住三件用户看得见的事：
 *   1. 开关开了到点**真的有通知**（不是"排上了但什么都没发生"）；
 *   2. 提醒和静音同时开时，课前**只有一条**通知，且静音结果写在里面；
 *   3. 静音没成 / 通知权限没给，记录里必须看得见（用户在外地只能读文件诊断）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class NudgeFlowTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // 干净起点：开关、闹钟、通知、日志全部清空
        NudgePrefs.setRemindEnabled(ctx, true)
        NudgePrefs.setSilenceEnabled(ctx, false)
        AlarmScheduler.cancelAll(ctx)
        nm().cancelAll()
        DiagLog.file(ctx).delete()
    }

    @After
    fun tearDown() {
        AlarmScheduler.cancelAll(ctx)
    }

    private fun nm(): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    private fun notifications(): List<Notification> =
        shadowOf(nm()).allNotifications

    private fun texts(): List<String> =
        notifications().mapNotNull { it.extras.getString(Notification.EXTRA_TEXT) }

    private fun titles(): List<String> =
        notifications().mapNotNull { it.extras.getString(Notification.EXTRA_TITLE) }

    private fun am() = shadowOf(ctx.getSystemService(AlarmManager::class.java))

    /** 一条 1 分钟后开始、1 小时后结束的提醒窗口（正文用线上的真实形状） */
    private fun window(): NudgeWindow {
        val start = LocalDateTime.now().plusMinutes(1).withNano(0)
        return NudgeWindow(
            start = start, end = start.plusHours(1), slotId = 1, courseId = 7,
            body = "程序设计基础（B） · 第1-2节 · 09:30 · A楼406",
        )
    }

    /** 把已排上的闹钟按时间取出来，返回 (动作, 原始 Intent) */
    private fun scheduled(): List<Pair<NudgeKind, android.content.Intent>> =
        am().scheduledAlarms.sortedBy { it.triggerAtTime }.map { al ->
            val intent = shadowOf(al.operation).savedIntent
            val kind = when (intent.action) {
                AlarmScheduler.ACTION_REMIND -> NudgeKind.REMIND
                AlarmScheduler.ACTION_SILENCE -> NudgeKind.SILENCE
                else -> NudgeKind.UNSILENCE
            }
            kind to intent
        }

    // ------------------------------------------------------------- 只开提醒

    @Test
    fun `只开提醒_排一个闹钟_到点真的弹出这节课的通知`() {
        val w = window()
        val n = AlarmScheduler.schedule(ctx, planActions(w, remind = true, silence = false))

        assertEquals(1, n)
        assertEquals("只开提醒时系统里就该只有 1 个待触发闹钟", 1, am().scheduledAlarms.size)
        assertEquals(1, AlarmScheduler.scheduledCount(ctx))

        val (kind, intent) = scheduled().single()
        assertEquals(NudgeKind.REMIND, kind)
        // 到点时刻 = 提前量算出来的时刻（毫秒级，别用字符串比）
        val expect = w.start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expect, am().scheduledAlarms.first().triggerAtTime)

        // ★ 真的走一遍被系统唤醒的那段代码
        NudgeReceiver().onReceive(ctx, intent)

        assertEquals("到点必须有一条通知 —— 否则这个功能等于没有", 1, notifications().size)
        assertEquals("1 分钟后上课", titles().single())
        val body = texts().single()
        assertTrue("通知要说清哪门课：$body", body.contains("程序设计基础（B）"))
        assertTrue("通知要说清在哪儿：$body", body.contains("A楼406"))

        val log = DiagLog.read(ctx, limit = 5)
        assertEquals(1, log.size)
        assertTrue("记录里要能看出是课前提醒：${log[0]}", log[0].what.startsWith("课前提醒"))
    }

    @Test
    fun `提前量改了_闹钟时刻跟着改`() {
        NudgePrefs.setLeadMinutes(ctx, 5)
        // 窗口本身就是"提前量已经算进去"的结果，这里验的是排程原样落到系统
        val w = NudgeWindow(
            start = LocalDateTime.now().plusMinutes(5).withNano(0),
            end = LocalDateTime.now().plusHours(2).withNano(0),
            slotId = 2, courseId = 7, body = "程序设计基础（B） · 第1-2节 · 09:30 · A楼406",
        )
        AlarmScheduler.schedule(ctx, planActions(w, remind = true, silence = false))
        val expect = w.start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expect, am().scheduledAlarms.first().triggerAtTime)
        NudgePrefs.setLeadMinutes(ctx, 1)
    }

    // ------------------------------------------------------------- 提醒 + 静音

    @Test
    fun `提醒和静音同时开_课前只有一条通知_静音结果写在里面`() {
        NudgePrefs.setSilenceEnabled(ctx, true)
        val w = window()
        val actions = planActions(w, remind = true, silence = true)
        assertEquals("课前提醒 + 下课恢复", 2, actions.size)
        assertEquals(2, AlarmScheduler.schedule(ctx, actions))

        val all = scheduled()
        NudgeReceiver().onReceive(ctx, all[0].second)   // 课前那一条

        assertEquals("同一时刻不许弹两条通知（那是噪音）", 1, notifications().size)
        assertEquals("1 分钟后上课", titles().single())
        val body = texts().single()
        assertTrue("静音成没成要写在提醒里：$body", body.contains(silenceNote(Silence.canWrite(ctx))))
        assertTrue("课名不能被挤掉：$body", body.contains("程序设计基础（B）"))
    }

    @Test
    fun `静音结果与记录一致_不许把失败记成成功`() {
        NudgePrefs.setSilenceEnabled(ctx, true)
        val w = window()
        AlarmScheduler.schedule(ctx, planActions(w, remind = true, silence = true))
        NudgeReceiver().onReceive(ctx, scheduled()[0].second)

        val line = DiagLog.read(ctx, limit = 5).first()
        val ok = Silence.canWrite(ctx)
        assertEquals("记录里的成败必须和真机上那次静音的实际结果一致", ok, line.ok)
        if (!ok) {
            assertEquals("no_write_settings", line.reason)
            assertTrue("用户要看懂为什么没静音：${texts().single()}",
                texts().single().contains("静音未生效"))
        }
        assertNotNull(SilenceDiag.explain("no_write_settings"))
    }

    // ------------------------------------------------------------- 只开静音

    @Test
    fun `只开静音_课前课后各一个闹钟_且课前那条会说自己干了什么`() {
        NudgePrefs.setRemindEnabled(ctx, false)
        NudgePrefs.setSilenceEnabled(ctx, true)
        val w = window()
        assertEquals(2, AlarmScheduler.schedule(ctx, planActions(w, remind = false, silence = true)))

        val all = scheduled()
        assertEquals(listOf(NudgeKind.SILENCE, NudgeKind.UNSILENCE), all.map { it.first })
        // 下课的恢复必须落在结束时刻
        val expectEnd = w.end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectEnd, am().scheduledAlarms.maxByOrNull { it.triggerAtTime }!!.triggerAtTime)

        NudgeReceiver().onReceive(ctx, all[0].second)
        assertEquals(1, notifications().size)
        val t = titles().single()
        val b = texts().single()
        if (Silence.canWrite(ctx)) {
            assertTrue("静音成功要在标题里报一句：$t", t.contains("已自动静音"))
            assertTrue("还要说清是哪节课：$b", b.contains("程序设计基础（B）"))
        } else {
            assertEquals("没权限时不许假装成功", "没能自动静音", t)
            assertTrue("要说清缺什么：$b", b.contains("修改系统设置"))
        }
    }

    // ------------------------------------------------------------- 关掉

    @Test
    fun `两个都关_系统里一个闹钟都不许剩`() {
        NudgePrefs.setRemindEnabled(ctx, true)
        AlarmScheduler.schedule(ctx, planActions(window(), remind = true, silence = false))
        assertTrue(am().scheduledAlarms.isNotEmpty())

        NudgePrefs.setRemindEnabled(ctx, false)
        AlarmScheduler.cancelAll(ctx)

        assertTrue("关掉后还留着闹钟 = 用户关了还在响", am().scheduledAlarms.isEmpty())
        assertEquals(0, AlarmScheduler.scheduledCount(ctx))
    }
}
