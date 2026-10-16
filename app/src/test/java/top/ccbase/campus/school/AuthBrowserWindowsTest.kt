package top.ccbase.campus.school

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AuthBrowserWindowsTest {
 @Test fun `gesture popup uses transport and returns to original session on close`() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  val root=WebView(activity);root.loadUrl(AustLoginPolicy.ENTRY)
  root.webViewClient=WebViewClient()
  var current=root
  val windows=AuthBrowserWindows(root,AustLoginPolicy::allowed,{current=it},{})
  assertTrue(root.settings.supportMultipleWindows())
  assertFalse(root.settings.javaScriptCanOpenWindowsAutomatically)
  assertFalse(root.settings.allowFileAccess)
  assertFalse(root.settings.allowContentAccess)
  assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,root.settings.mixedContentMode)
  assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(root))
  val msg=Message.obtain(Handler(Looper.getMainLooper()));val transport=root.WebViewTransport();msg.obj=transport
  assertFalse(root.webChromeClient!!.onCreateWindow(root,false,false,msg))
  assertTrue(root.webChromeClient!!.onCreateWindow(root,false,true,msg))
  val child=transport.webView!!
  assertNotSame(root,child);assertSame(child,current)
  assertSame(root.webViewClient,child.webViewClient)
  assertFalse(child.settings.allowFileAccess)
  assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(child))
  assertFalse(child.webChromeClient!!.onCreateWindow(child,false,true,msg))
  val chrome=(child.parent as android.view.ViewGroup).getChildAt(0)
  val originalHeight=child.height
  fun drag(y:Float,a:Int=android.view.MotionEvent.ACTION_MOVE){val e=android.view.MotionEvent.obtain(0,16,a,100f,y,0);child.dispatchTouchEvent(e);e.recycle()}
  drag(300f,android.view.MotionEvent.ACTION_DOWN);drag(270f)
  assertTrue(chrome.alpha in 0f..1f);assertTrue(chrome.alpha<1f && chrome.alpha>0f)
  drag(10f);assertEquals(android.view.View.INVISIBLE,chrome.visibility)
  assertEquals(originalHeight,child.height)
  drag(200f);assertEquals(1f,chrome.alpha,0f)
  assertTrue(windows.back());assertSame(root,current);assertFalse(windows.back())
  val second=Message.obtain(Handler(Looper.getMainLooper())).apply{obj=root.WebViewTransport()}
  assertTrue(root.webChromeClient!!.onCreateWindow(root,false,true,second))
  windows.dispose();assertSame(root,current);assertFalse(windows.back())
  root.destroy();activity.finish()
 }
}
