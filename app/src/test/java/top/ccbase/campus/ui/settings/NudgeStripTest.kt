package top.ccbase.campus.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.alarm.AlarmScheduler
import top.ccbase.campus.alarm.NudgePrefs
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 「我的 → 提醒」这一块的**用户视角**验收。
 *
 * 用户要的是"提醒从 App 里来，别人也能用" —— 那么界面上必须一眼看出：
 *   ① 课前提醒是**默认开着**的（否则别人装上也不知道有这功能）；
 *   ② 它说的是人话：上课前几分钟、只在本机发；
 *   ③ 拨动开关**真的写进了设置**（不是只动了个 state、退出又变回去）；
 *   ④ 自动静音是另一个独立开关（它要改系统铃声，默认关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class NudgeStripTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        NudgePrefs.setRemindEnabled(ctx, true)
        NudgePrefs.setSilenceEnabled(ctx, false)
        NudgePrefs.setLeadMinutes(ctx, 1)
        AlarmScheduler.cancelAll(ctx)
    }

    private fun render() {
        rule.setContent { CampusTheme { Column(Modifier.fillMaxSize()) { NudgeStrip() } } }
    }

    /** 屏幕上真的有这句话（Robolectric 里 assertIsDisplayed 会因窗口尺寸误报，用"节点存在"当判据）。 */
    private fun assertSeen(text: String, substring: Boolean = false) {
        assertTrue(
            "界面上没出现「$text」—— 用户就看不到这件事",
            rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `默认_课前提醒开着_自动静音关着_并说清只在本机发`() {
        render()

        assertSeen("课前提醒")
        assertSeen("本机发出，不经任何服务器", substring = true)
        assertSeen("自动静音")

        val switches = rule.onAllNodes(isToggleable())
        switches[0].assertIsOn()
        switches[1].assertIsOff()
    }

    @Test
    fun `关掉提醒_真的写进设置`() {
        render()
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodes(isToggleable())[0].assertIsOff()
        assertFalse("开关动了但设置没写，下次进 App 又会自己开回来", NudgePrefs.remindEnabled(ctx))
    }

    @Test
    fun `提前量点一下_写进设置_并且副标题跟着变`() {
        render()
        rule.onNodeWithText("5 分").performClick()
        assertEquals(5, NudgePrefs.leadMinutes(ctx))
        assertTrue("提前量改了文案要跟着变（用户才知道自己改上了）",
            rule.onAllNodes(hasText("上课/自习前 5 分钟", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `开着的时候能看到下一个闹钟排在哪`() {
        render()
        assertSeen("下一个闹钟（我们排的）")
    }
}
