package top.ccbase.campus.ui
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.ui.onboard.WelcomeScreen
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 正式版只有一所学校：克校。
 *
 * 这里钉死的是「上一版把多学校测试面放进正式包」那件事不再复现：
 * 欢迎页不许出现学校搜索框、学校选择链、外校测试登录入口；
 * 主按钮必须是克校课表导入，一点就进流程（不需要先选校）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SchoolChoiceTest {
 @get:Rule val rule=createComposeRule()
 private val ctx:Context=ApplicationProvider.getApplicationContext()
 @Before fun clear(){ctx.getSharedPreferences("school_choice",0).edit().clear().commit()}
 @After fun clean(){clear()}

 @Test fun `欢迎页不再要求选学校且只提供克校导入`() {
  var started=false
  rule.setContent{CampusTheme{WelcomeScreen({started=true}, {})}}
  rule.onNodeWithText("导入课表（克拉玛依校区教务）").assertExists().performClick()
  Assert.assertTrue("点主按钮就该进导入流程，不需要先选学校", started)
  assertTrue(rule.onAllNodesWithText("搜索学校").fetchSemanticsNodes().isEmpty())
  assertTrue(rule.onAllNodesWithText("选择或更换学校").fetchSemanticsNodes().isEmpty())
  assertTrue(rule.onAllNodesWithText("安徽").fetchSemanticsNodes().isEmpty())
  assertTrue(rule.onAllNodesWithText("东北农业").fetchSemanticsNodes().isEmpty())
  Assert.assertNull(ctx.getSharedPreferences("school_choice",0).getString("id",null))
 }
}
