package top.ccbase.campus.ui
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.onboard.WelcomeScreen
import top.ccbase.campus.ui.theme.CampusTheme
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SchoolChoiceTest {
 @get:Rule val rule=createComposeRule()
 private val ctx:Context=ApplicationProvider.getApplicationContext()
 @Before fun clear(){ctx.getSharedPreferences("school_choice",0).edit().clear().commit()}
 @After fun clean(){clear()}
 @Test fun `首次选校保存且入口名称跟随学校`() {
  rule.setContent{CampusTheme{WelcomeScreen({}, {})}}
  rule.onNodeWithText("搜索学校").assertExists()
  rule.onNodeWithText("学校名称／省份／学校代码").performTextInput("东北农业大学")
  rule.onNodeWithText("东北农业大学 · 黑龙江省 · 本科").performClick()
  rule.onNodeWithText("东北农业大学 · 测试官方登录").assertExists()
  Assert.assertEquals("neau",ctx.getSharedPreferences("school_choice",0).getString("id",null))
  rule.onNodeWithText("选择或更换学校").performClick()
  rule.onNodeWithText("学校名称／省份／学校代码").performTextInput("安徽工业大学")
  rule.onNodeWithText("安徽工业大学 · 安徽省 · 本科").performClick()
  rule.onNodeWithText("安徽工业大学 · 测试官方登录").assertExists()
 }
}
