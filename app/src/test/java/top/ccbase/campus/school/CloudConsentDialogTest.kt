package top.ccbase.campus.school

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.theme.CampusTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudConsentDialogTest {
    // Register the JVM-only Compose host before ActivityScenario starts. Release
    // intentionally has no ui-test-manifest dependency or exported test Activity.
    @get:Rule(order = 0) val host = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(context.packageManager).addActivityIfNotPresent(
                android.content.ComponentName(context, androidx.activity.ComponentActivity::class.java)
            )
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    @Test fun `未点击同意不会上传_拒绝只关闭`() {
        var upload = 0
        var cancelled = 0
        rule.setContent { CampusTheme { CloudConsentDialog({ cancelled++ }, { upload++ }) } }
        rule.waitForIdle()
        assertEquals(0,upload)
        rule.onNodeWithText("不同意，仅本机使用").performClick()
        assertEquals(1,cancelled)
        assertEquals(0,upload)
    }
    @Test fun `必须明确点击同意才执行上传回调`() {
        var upload = 0
        rule.setContent { CampusTheme { CloudConsentDialog({}, { upload++ }) } }
        rule.waitForIdle()
        assertEquals(0,upload)
        rule.onNodeWithText("同意并同步").performClick()
        assertEquals(1,upload)
    }
}
