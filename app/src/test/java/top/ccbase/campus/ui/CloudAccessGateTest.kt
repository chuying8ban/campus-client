package top.ccbase.campus.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.common.CloudAccessGate
import top.ccbase.campus.ui.theme.CampusTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudAccessGateTest {
 @get:Rule val rule = createComposeRule()
 @Test fun `拒绝不跳转_选择同步才跳转`() {
  var close = 0; var go = 0
  rule.setContent { CampusTheme { CloudAccessGate({ close++ }, { go++ }) } }
  rule.onNodeWithText("此功能需要云端同步").assertExists()
  assertEquals(0,go)
  rule.onNodeWithText("继续本机使用").performClick()
  assertEquals(1,close);assertEquals(0,go)
  rule.onNodeWithText("去导入与同步").performClick()
  assertEquals(1,go)
 }
 @Test fun `源码禁止旧登录与自动教务承诺`() {
  val root=java.io.File("src/main/java")
  val text=root.walkTopDown().filter { it.isFile && it.extension=="kt" }.joinToString("\n") { it.readText() }
  assertFalse(text.contains("服务器每天从教务系统同步课表"))
  assertFalse(text.contains("先退出重登一次"))
  assertFalse(text.contains("课表每天会自动更新"))
 }
}
