package top.ccbase.campus.ui.library
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.net.*
import top.ccbase.campus.ui.theme.CampusTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WebLearningSearchTest {
 @get:Rule val rule=createComposeRule()
 private lateinit var db:CampusDb
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),CampusDb::class.java).allowMainThreadQueries().build()}
 @After fun close(){db.close()}
 @Test fun `搜索真实提交关键词并渲染来源摘要`() {
  var received=""
  rule.setContent{CampusTheme{WebLearningSearch(db){q,k->received=q;ApiResult.Ok(LearningSearchResult(query=q,items=listOf(LearningSearchItem("指针学习教程","https://www.runoob.com/cprogramming/c-pointers.html","变量地址和指针的教程","www.runoob.com"))))}}}
  rule.onNodeWithTag("web-search-input").performTextInput("C语言 指针")
  rule.onNodeWithTag("web-search-submit").performClick()
  rule.waitUntil(5000){rule.onAllNodesWithText("指针学习教程").fetchSemanticsNodes().isNotEmpty()}
  Assert.assertEquals("C语言 指针",received)
  rule.onNodeWithText("www.runoob.com · 文档").assertExists()
  rule.onNodeWithText("变量地址和指针的教程").assertExists()
 }
 @Test fun `点击其他区域取消焦点且保留关键词`() {
  rule.setContent{CampusTheme{WebLearningSearch(db){_,_->ApiResult.Err(503,"暂不可用")}}}
  rule.onNodeWithTag("web-search-input").performClick().performTextInput("指针")
  rule.onNodeWithTag("web-search-input").assertIsFocused()
  rule.onNodeWithTag("search-kind-all").performClick()
  rule.waitForIdle()
  rule.waitUntil(5000){rule.onNodeWithTag("web-search-input").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused]==false}
  rule.onNodeWithTag("web-search-input").assertIsNotFocused().assertTextContains("指针")
 }
 @Test fun `分类筛同一合集全部包含所有类型`() {
  var calls=0
  rule.setContent{CampusTheme{WebLearningSearch(db){q,k->calls++;Assert.assertEquals("all",k);ApiResult.Ok(LearningSearchResult(query=q,items=listOf(
   LearningSearchItem("文档资源","https://example.org/doc","摘要","example.org",kind="document"),
   LearningSearchItem("视频资源","https://example.org/video","摘要","example.org",kind="video"),
   LearningSearchItem("练习资源","https://example.org/practice","摘要","example.org",kind="practice")
  )))}}}
  rule.onNodeWithTag("web-search-input").performTextInput("高等数学")
  rule.onNodeWithTag("web-search-submit").performClick()
  rule.waitUntil(5000){rule.onAllNodesWithText("文档资源").fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("视频课程").performClick()
  rule.onNodeWithText("视频资源").assertExists()
  rule.onNodeWithText("文档资源").assertDoesNotExist()
  rule.onNodeWithTag("search-kind-all").performClick()
  rule.waitForIdle()
  rule.waitUntil(5000){rule.onAllNodesWithText("文档资源").fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("文档资源").assertExists()
  rule.onNodeWithText("视频资源").assertExists()
  Assert.assertEquals(1,calls)
 }
 @Test fun `失败必须可见而不是伪装空结果`() {
  rule.setContent{CampusTheme{WebLearningSearch(db){_,_->ApiResult.Err(503,"搜索源不可用")}}}
  rule.onNodeWithTag("web-search-input").performTextInput("高等数学")
  rule.onNodeWithTag("web-search-submit").performClick()
  rule.waitUntil(5000){rule.onAllNodesWithText("搜索未成功（503）：搜索源不可用").fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("重试").assertExists()
 }
}
