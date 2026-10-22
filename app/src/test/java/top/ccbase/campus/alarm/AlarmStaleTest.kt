package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.ccbase.campus.domain.NudgeWindow
import top.ccbase.campus.domain.planActions
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「闹钟到底响没响」的记账 —— 看门狗判"陈旧"（该响没响）的依据。
 *
 * 2026-10-22 用户报「铃声恢复有延迟」的**间接元凶**在这里：
 * 旧实现只记住"排程时最早那一条"（`K_NEXT`），它响过之后永远停在过去 ——
 * 于是每天第一条闹钟响完 10 分钟起，看门狗就认定"闹钟丢了"，每 15 分钟全量重排一次；
 * 每次重排都 `cancelAll`，把**正在上的这节课**的恢复铃声一起撤掉。
 *
 * 现在每条响过的闹钟会把自己从时刻表里划掉，"下一个"跟着往前走：
 * 只有真的漏响（或整张表响完了）才判陈旧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class AlarmStaleTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NudgePrefs.setRemindEnabled(ctx, true)
        NudgePrefs.setSilenceEnabled(ctx, true)
        AlarmScheduler.cancelAll(ctx)
        (ctx.getSystemService(NotificationManager::class.java)).cancelAll()
        DiagLog.file(ctx).delete()
    }

    private fun am() = shadowOf(ctx.getSystemService(AlarmManager::class.java))

    private fun firstAlarm(): android.content.Intent =
        shadowOf(am().scheduledAlarms.minByOrNull { it.triggerAtTime }!!.operation).savedIntent

    private fun window(): NudgeWindow {
        val start = LocalDateTime.now().plusMinutes(10).withNano(0)
        return NudgeWindow(start = start, end = start.plusMinutes(45), slotId = 1, courseId = 1, body = "高等数学")
    }

    // ---------------------------------------------------------------- 判定本体（纯函数）

    private val now = 1_700_000_000_000L

    @Test
    fun `一个都没排上_判陈旧`() {
        assertTrue(AlarmScheduler.staleOf(count = 0, times = emptyList(), nowMs = now))
    }

    @Test
    fun `排的全响完了_判陈旧_该续下一周`() {
        assertTrue(AlarmScheduler.staleOf(count = 3, times = emptyList(), nowMs = now))
    }

    @Test
    fun `下一条还在未来_不算陈旧（旧实现这里会误判）`() {
        val times = listOf(now + 30 * 60_000L, now + 90 * 60_000L)
        assertFalse(AlarmScheduler.staleOf(count = 2, times = times, nowMs = now))
    }

    @Test
    fun `该响的那条过了10分钟还没响_才判陈旧`() {
        assertTrue(AlarmScheduler.staleOf(count = 2, times = listOf(now - 11 * 60_000L, now + 60 * 60_000L), nowMs = now))
        assertFalse("刚过点 5 分钟要给系统一点余量", AlarmScheduler.staleOf(count = 1, times = listOf(now - 5 * 60_000L), nowMs = now))
    }

    // ---------------------------------------------------------------- 落盘：响过就划掉

    @Test
    fun `闹钟真响了_把自己从时刻表里划掉_下一个跟着往前走`() {
        AlarmScheduler.schedule(ctx, planActions(window(), remind = true, silence = true))
        val before = AlarmScheduler.scheduledTimes(ctx)
        assertEquals("课前提醒 + 下课恢复", 2, before.size)
        assertFalse(AlarmScheduler.isStale(ctx))

        NudgeReceiver().onReceive(ctx, firstAlarm())

        val after = AlarmScheduler.scheduledTimes(ctx)
        assertEquals("响过的那条不许留在表里（留着就会天天误判陈旧）", 1, after.size)
        assertEquals("剩下的必须是最晚那条", before.last(), after.single())
        assertEquals("我们的下一个闹钟要跟着前移", before.last(), after.first())
        assertFalse("后面还有排程，不许判陈旧", AlarmScheduler.isStale(ctx))
    }

    @Test
    fun `全响完之后_时刻表被清空并判陈旧`() {
        val w = window()
        AlarmScheduler.schedule(ctx, planActions(w, remind = true, silence = true))
        val endMs = w.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 先响课前那条，再响下课那条（晚响也照原定时刻算）
        NudgeReceiver().onReceive(ctx, firstAlarm())
        val rest = am().scheduledAlarms.maxByOrNull { it.triggerAtTime }!!
        NudgeReceiver().onReceive(ctx, shadowOf(rest.operation).savedIntent)

        assertTrue("表空了＝这一批全响完，下次巡检要续下一周", AlarmScheduler.scheduledTimes(ctx).isEmpty())
        assertTrue(AlarmScheduler.isStale(ctx))
        assertTrue("记录里两条触发都得留痕", DiagLog.read(ctx, limit = 10).size >= 2)
        assertTrue(endMs > 0)
    }
}
