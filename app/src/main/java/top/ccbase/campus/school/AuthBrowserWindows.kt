package top.ccbase.campus.school

import android.app.Dialog
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

/** Same WebView profile/transport; no browser intents, cookie export or console capture. */
internal class AuthBrowserWindows(
 private val root: WebView,
 private val allowed: (String) -> Boolean,
 private val active: (WebView) -> Unit,
 private val event: (String) -> Unit,
 private val beforeNavigation: (WebView) -> Unit = {},
 private val toolbarBlocked: () -> Boolean = {false}
) {
 private data class Child(val web:WebView,val dialog:Dialog,val address:TextView)
 private val children=mutableListOf<Child>()
 init { install(root) }
 private fun install(view:WebView) {
  configure(view)
  beforeNavigation(view)
  view.webChromeClient=object:WebChromeClient(){
   override fun onConsoleMessage(message:ConsoleMessage):Boolean=true // never read/log raw console
   override fun onCreateWindow(view:WebView,isDialog:Boolean,isUserGesture:Boolean,resultMsg:Message):Boolean {
    if(!isUserGesture || !allowed(view.url.orEmpty()) || children.isNotEmpty()) {event("子窗口请求已拒绝");return false}
    val transport=resultMsg.obj as? WebView.WebViewTransport ?: return false
    val child=WebView(root.context)
    child.webViewClient=root.webViewClient
    install(child)
    val dialog=Dialog(root.context)
    val layout=LinearLayout(root.context).apply{orientation=LinearLayout.VERTICAL}
    val address=TextView(root.context).apply{text="认证子窗口 · 等待HTTPS地址"}
    layout.addView(address)
    val closeButton=Button(root.context).apply{text="关闭认证子窗口";setOnClickListener{close(child)}}
    layout.addView(closeButton)
    val chrome=LinearLayout(root.context).apply{orientation=LinearLayout.VERTICAL}
    layout.removeView(address);layout.removeView(closeButton)
    chrome.addView(address);chrome.addView(closeButton)
    layout.addView(chrome)
    var chromeProgress=1f
    val gesture=BrowserToolbarGesture(child,blocked=toolbarBlocked,travel=80f*root.resources.displayMetrics.density){fraction->
     chromeProgress=fraction
     chrome.translationY=-(1f-fraction)*chrome.height
     chrome.alpha=fraction
     chrome.visibility=if(fraction==0f)android.view.View.INVISIBLE else android.view.View.VISIBLE
    }
    child.setOnTouchListener(gesture)
    layout.addView(child,LinearLayout.LayoutParams(-1,0,1f))
    dialog.setContentView(layout)
    val record=Child(child,dialog,address)
    children.add(record)
    dialog.setCancelable(false)
    dialog.setOnKeyListener{_,key,e->
     if(key==android.view.KeyEvent.KEYCODE_BACK){if(e.action==android.view.KeyEvent.ACTION_UP){if(chromeProgress<1f){gesture.restore()}else back()};true}else false
    }
    dialog.setOnDismissListener{close(child)}
    dialog.show();dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
    active(child)
    transport.webView=child;resultMsg.sendToTarget();event("认证子窗口已在App内打开")
    return true
   }
   override fun onCloseWindow(window:WebView){close(window)}
  }
 }
 fun navigated(view:WebView,url:String) {
  children.firstOrNull{it.web===view}?.address?.text=if(allowed(url)) "认证子窗口 · HTTPS · ${java.net.URI(url).host}" else "认证子窗口 · 地址已拦截"
 }
 fun back():Boolean {
  val child=children.lastOrNull() ?: return false
  if(child.web.canGoBack())child.web.goBack() else close(child.web)
  return true
 }
 private fun close(view:WebView) {
  val record=children.firstOrNull{it.web===view} ?: return
  children.remove(record) // detach first: dismiss callback is reentrant
  record.dialog.setOnDismissListener(null);record.dialog.dismiss()
  (view.parent as? ViewGroup)?.removeView(view)
  view.stopLoading();view.clearHistory();view.destroy()
  active(children.lastOrNull()?.web ?: root)
  event("认证子窗口已关闭")
 }
 fun prepareDiagnostics(){beforeNavigation(root);children.forEach{beforeNavigation(it.web)}}
 fun dispose(){children.toList().asReversed().forEach{close(it.web)}}
 companion object {
  @Suppress("DEPRECATION")
  fun configure(view:WebView) {
   view.settings.apply {
    javaScriptEnabled=true;domStorageEnabled=true
    setSupportMultipleWindows(true)
    javaScriptCanOpenWindowsAutomatically=false
    useWideViewPort=true;loadWithOverviewMode=true
    allowFileAccess=false;allowContentAccess=false
    allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false
    mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
    safeBrowsingEnabled=true
   }
   CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
  }
 }
}
