package top.ccbase.campus.ui.library
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.theme.CampusTheme
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ResourceBrowserTest {
 @get:Rule val rule=createComposeRule()
 @Test fun `阅读模式隐藏两栏且能恢复`() {
  rule.setContent{CampusTheme{ResourceBrowser("https://example.org"){}}}
  rule.onNodeWithText("隐藏上下栏").performClick()
  rule.onNodeWithText("关闭").assertDoesNotExist()
  rule.onNodeWithText("下载当前链接").assertDoesNotExist()
  rule.onNodeWithText("显示工具栏").performClick()
  rule.onNodeWithText("关闭").assertExists()
  rule.onNodeWithText("下载当前链接").assertExists()
 }
}
