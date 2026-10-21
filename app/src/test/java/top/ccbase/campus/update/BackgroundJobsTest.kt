package top.ccbase.campus.update

import android.app.job.JobScheduler
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.alarm.Silence
import top.ccbase.campus.alarm.SilenceWatchdog

/**
 * 两条「后台」能力到底有没有真的排上：静音看门狗 + 后台静默更新任务。
 *
 * 背景（2026-10-21 用户报的两件事）：
 *  · 「下课后静音没有自动恢复」→ 恢复闹钟被 60 条上限截掉（另有用例守），
 *    这里守的是**第二道保险**：15 分钟的定时巡检真的排上了、真的会把铃声还回来；
 *  · 「有些用户下载新更新时并没有做到后台下载」→ Android 12+ 后台起不了前台服务，
 *    所以静默更新必须同时挂一条 JobScheduler 任务，任务在跑时进程不会被冻结。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundJobsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val js get() = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val watchdogJob = 7301
    private val updateJob = 7302

    @Before
    fun setUp() {
        top.ccbase.campus.update.AutoUpdate.setEnabled(ctx, false)
        ctx.getSharedPreferences("silence_mark", Context.MODE_PRIVATE).edit().clear().apply()
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun tearDown() = setUp()

    // ───────────────────────── 静音看门狗 ─────────────────────────

    @Test
    fun `看门狗会排成系统周期任务`() {
        SilenceWatchdog.ensure(ctx)
        val job = js.allPendingJobs.firstOrNull { it.id == watchdogJob }
        assertTrue("15 分钟的巡检任务必须真的排上，否则闹钟丢了就只能静到第二天", job != null)
        assertTrue("必须是周期任务", job!!.isPeriodic)
        assertEquals("最小周期 15 分钟", 15 * 60 * 1000, job.intervalMillis)
        assertTrue("重启后要还在", job.isPersisted)
    }

    @Test
    fun `巡检一次_过期还静着就把铃声还回来`() {
        // 造出"我们在静音、而且这一段早就该结束了"的状态
        val prefs = ctx.getSharedPreferences("silence_mark", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("ours", true)
            .putInt("prev", AudioManager.RINGER_MODE_NORMAL)
            .putLong("until", System.currentTimeMillis() - 5 * 60_000L)
            .putLong("start", System.currentTimeMillis() - 6 * 60_000L)
            .apply()
        am.ringerMode = AudioManager.RINGER_MODE_SILENT

        val healed = SilenceWatchdog.runOnce(ctx)

        assertTrue("这一轮必须动手恢复", healed)
        assertEquals("铃声要回到正常", AudioManager.RINGER_MODE_NORMAL, am.ringerMode)
        assertFalse("标记要清掉，不然每一轮都会再恢复一次", prefs.getBoolean("ours", false))
    }

    @Test
    fun `还没到下课时间_巡检不许提前放铃声`() {
        val prefs = ctx.getSharedPreferences("silence_mark", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("ours", true)
            .putInt("prev", AudioManager.RINGER_MODE_VIBRATE)
            .putLong("until", System.currentTimeMillis() + 20 * 60_000L)
            .apply()
        am.ringerMode = AudioManager.RINGER_MODE_SILENT

        assertFalse("课还没下，不能动", SilenceWatchdog.runOnce(ctx))
        assertEquals("还在静音", AudioManager.RINGER_MODE_SILENT, am.ringerMode)
    }

    @Test
    fun `不是我们按的静音_巡检绝不碰`() {
        // K_OURS 未置位：用户自己按的静音，App 没有资格改
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertFalse(SilenceWatchdog.runOnce(ctx))
        assertEquals(AudioManager.RINGER_MODE_SILENT, am.ringerMode)
    }

    // ───────────────────────── 静默更新 ─────────────────────────

    @Test
    fun `开启自动更新_闹钟任务两条路都排上`() {
        AutoUpdate.setEnabled(ctx, true)
        assertTrue("JobScheduler 那条要排上（后台起不了前台服务时全靠它）",
            js.allPendingJobs.any { it.id == updateJob })
        assertTrue("6 小时的周期任务", js.allPendingJobs.first { it.id == updateJob }.isPeriodic)
        assertTrue("重启后要还在", js.allPendingJobs.first { it.id == updateJob }.isPersisted)
    }

    @Test
    fun `关掉自动更新_任务要撤掉`() {
        AutoUpdate.setEnabled(ctx, true)
        assertTrue(js.allPendingJobs.any { it.id == updateJob })
        AutoUpdate.setEnabled(ctx, false)
        assertFalse("关了就别再跑后台任务", js.allPendingJobs.any { it.id == updateJob })
    }

    @Test
    fun `开关没开时_任务不许自己排上`() {
        AutoUpdate.setEnabled(ctx, false)
        UpdateJobService.ensure(ctx)
        assertFalse(js.allPendingJobs.any { it.id == updateJob })
    }
}
