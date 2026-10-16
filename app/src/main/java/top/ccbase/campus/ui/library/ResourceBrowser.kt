package top.ccbase.campus.ui.library
import android.annotation.SuppressLint
import android.webkit.*
import android.net.http.SslError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.util.Links

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ResourceBrowser(url: String, onClose: () -> Unit) {
 val ctx=LocalContext.current
 var view by remember {mutableStateOf<WebView?>(null)}
 var current by remember {mutableStateOf(url)}
 var error by remember {mutableStateOf<String?>(null)}
 var progress by remember { mutableIntStateOf(0) }
 var reading by remember { mutableStateOf(false) }
 var download by remember {mutableStateOf<String?>(null)}
 androidx.activity.compose.BackHandler {if(view?.canGoBack()==true)view?.goBack() else onClose()}
 DisposableEffect(Unit){onDispose{view?.stopLoading();view?.destroy();view=null}}
 androidx.compose.ui.window.Dialog(onDismissRequest={if(view?.canGoBack()==true)view?.goBack() else onClose()},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
 Box(Modifier.fillMaxSize().overlaySurface) {
 Column(Modifier.fillMaxSize()) {
 if(!reading) {
  Row {TextButton(onClick=onClose){Text("关闭")};TextButton(onClick={view?.goBack()}){Text("后退")};TextButton(onClick={view?.reload()}){Text("刷新")};TextButton(onClick={Links.open(ctx,current)}){Text("外部打开")}}
  TextButton(onClick={reading=true}){Text("隐藏上下栏")}
  Text(current,color=C.txt2,modifier=Modifier.padding(horizontal=12.dp),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
 }
  if(progress in 0..99) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
  error?.let{Text(it,color=C.txt2,modifier=Modifier.padding(12.dp))}
  AndroidView(factory={c->WebView(c).apply {
   view=this
   settings.javaScriptEnabled=true
   settings.domStorageEnabled=true
   settings.allowFileAccess=false;settings.allowContentAccess=false
   settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
   settings.setSupportZoom(true);settings.builtInZoomControls=true;settings.displayZoomControls=false
   settings.safeBrowsingEnabled=true
   android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
   settings.useWideViewPort=true;settings.loadWithOverviewMode=true
   webChromeClient=object:WebChromeClient(){override fun onProgressChanged(v:WebView,p:Int){progress=p}}
   webViewClient=object:WebViewClient(){
    override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean {
     val s=r.url.scheme.orEmpty();return s!="https"&&s!="http"
    }
    override fun onPageStarted(v:WebView,u:String,f:android.graphics.Bitmap?){current=u;error=null}
    override fun onPageFinished(v:WebView,u:String){current=u}
    override fun onReceivedSslError(v:WebView,h:SslErrorHandler,e:SslError){h.cancel();error="网页证书校验失败，已停止加载"}
    override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame)error="网页加载失败，可重试或使用外部浏览器"}
   }
   setDownloadListener{u,_,_,_,_->download=u}
   if(android.net.Uri.parse(url).scheme?.lowercase() in listOf("http","https"))loadUrl(url) else error="不支持此链接"
  } }, modifier=Modifier.weight(1f).fillMaxWidth())
  if(!reading) TextButton(onClick={download=current}){Text("下载当前链接")}
 }
 if(reading) FilledTonalButton(onClick={reading=false},modifier=Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp)){Text("显示工具栏")}
 }
 download?.let{u->AlertDialog(onDismissRequest={download=null},title={Text("下载链接")},text={Text("直接文件链接可下载资料；普通网页只会下载网页，不会自动提取视频，也不绕过登录或付费限制。下载会使用网络流量。")},confirmButton={TextButton(onClick={Links.download(ctx,u);download=null}){Text("下载")}},dismissButton={TextButton(onClick={download=null}){Text("取消")}})}
 }
}
