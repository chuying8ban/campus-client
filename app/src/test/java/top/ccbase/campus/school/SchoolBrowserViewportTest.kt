package top.ccbase.campus.school

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w360dp-h640dp-mdpi")
class SchoolBrowserViewportTest {
 @get:Rule val rule=createComposeRule()
 @Test fun measuredViewportKeepsSpaceAtNormalSmallAndKeyboardSizes() {
  var height by mutableStateOf(640)
  lateinit var root:View
  rule.setContent { root=LocalView.current; MaterialTheme { Box(Modifier.requiredSize(360.dp,height.dp)) { AustLogin(close={}) } } }
  fun find(v:View):WebView? { if(v is WebView)return v; if(v is ViewGroup)for(i in 0 until v.childCount){find(v.getChildAt(i))?.let{return it}};return null }
  fun swipe(up:Boolean) { rule.runOnIdle { val web=find(root)!!; val y=if(up)200f else 50f; for((a,p) in listOf(0 to y,2 to (if(up)50f else 200f),1 to (if(up)50f else 200f))) {val e=MotionEvent.obtain(0,16,a,100f,p,0);web.dispatchTouchEvent(e);e.recycle()} };rule.waitForIdle() }
  for(h in listOf(640,480,320)) {
   rule.runOnIdle { height=h };rule.waitForIdle()
   val b=rule.onNodeWithTag("schoolWebViewport").fetchSemanticsNode().boundsInRoot
   println("VIEWPORT container=360x$h measured=${b.width}x${b.height} top=${b.top}")
   assertTrue("WebView consumed by native chrome at $h: $b",b.height>=h-60)
   rule.onNodeWithText("信息").performClick();rule.waitForIdle()
   val during=rule.onNodeWithTag("schoolWebViewport").fetchSemanticsNode().boundsInRoot
   assertEquals("Opening an overlay must not resize WebView",b,during)
   rule.onNodeWithText("返回网页").performClick();rule.waitForIdle()
   assertEquals(b,rule.onNodeWithTag("schoolWebViewport").fetchSemanticsNode().boundsInRoot)
   swipe(true)
   rule.onNodeWithText("信息").assertDoesNotExist()
   val full=rule.onNodeWithTag("schoolWebViewport").fetchSemanticsNode().boundsInRoot
   println("HIDDEN container=360x$h measured=${full.width}x${full.height}")
   assertEquals("Gesture must not resize the live page",b.height,full.height,1f)
   swipe(false)
   rule.onNodeWithText("信息").assertExists()
   assertEquals(b,rule.onNodeWithTag("schoolWebViewport").fetchSemanticsNode().boundsInRoot)
  }
 }
}
