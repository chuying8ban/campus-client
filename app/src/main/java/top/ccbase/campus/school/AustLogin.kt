package top.ccbase.campus.school

import android.webkit.*
import android.net.http.SslError
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import java.net.URI
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object AustLoginPolicy {
 const val ENTRY="https://authserver.aust.edu.cn/authserver/login"
 fun allowed(url:String):Boolean=runCatching{val u=URI(url);u.scheme=="https" && u.host=="authserver.aust.edu.cn" && u.rawUserInfo==null && (u.port==-1||u.port==443)}.getOrDefault(false)
}
/** Authentication-only pilot. No password bridge, cookie upload or guessed success detection. */
@Composable fun AustLogin(schoolName:String="安徽理工大学",entry:String=AustLoginPolicy.ENTRY,hosts:Set<String> = setOf("authserver.aust.edu.cn"),onTimetable:((String)->Unit)?=null,close:()->Unit) {
 fun allowed(url:String)=runCatching{val u=URI(url);u.scheme=="https" && u.host in hosts && u.rawUserInfo==null && (u.port==-1||u.port==443)}.getOrDefault(false)
 val context=androidx.compose.ui.platform.LocalContext.current
 var toolbarProgress by remember{mutableFloatStateOf(1f)}
 val toolbarVisible=toolbarProgress>0f
 var toolbarGesture by remember{mutableStateOf<BrowserToolbarGesture?>(null)}
 var showInfo by remember{mutableStateOf(false)}
 var diagnostic by remember{mutableStateOf<String?>(null)}
 var diagnosing by remember{mutableStateOf(false)}
 var diagnosticRequest by remember{mutableStateOf(0)}
 val session=remember{LoginDiagnosticSession(defaultEnabled=top.ccbase.campus.BuildConfig.SCHOOL_TEST)}
 var diagnosticEnabled by remember{mutableStateOf(session.active)}
 var diagnosticTelemetry by remember{mutableStateOf<kotlinx.serialization.json.JsonObject?>(null)}
 val documentStart=remember(hosts){AuthDocumentStart(hosts).apply{enabled=session.active}}
 val events=remember{mutableStateListOf<String>()}
 fun event(kind:String,url:String="") {if(!session.active)return;events.add(kind+if(url.isBlank()) "" else " · "+SchoolDiagnostics.location(url));while(events.size>12)events.removeAt(0)}
 var web by remember{mutableStateOf<WebView?>(null)}
 var rootWeb by remember{mutableStateOf<WebView?>(null)}
 var windows by remember{mutableStateOf<AuthBrowserWindows?>(null)}
 var error by remember{mutableStateOf<String?>(null)}
 var loading by remember{mutableStateOf(true)}
 var confirmClear by remember{mutableStateOf(false)}
 var extracted by remember{mutableStateOf<String?>(null)}
 var reading by remember{mutableStateOf(false)}
 var readGeneration by remember{mutableStateOf(0)}
 var diagnosticTrigger by remember{mutableStateOf(0)}
 var diagnosticPage by remember{mutableStateOf("")}
 var diagnosticEvents by remember{mutableStateOf<List<String>>(emptyList())}
 var diagnosticResult by remember{mutableStateOf<String?>(null)}
 var receiptId by remember{mutableStateOf(java.util.UUID.randomUUID().toString())}
 var sending by remember{mutableStateOf(false)}
 var sendStatus by remember{mutableStateOf<String?>(null)}
 val scope=rememberCoroutineScope()
 LaunchedEffect(diagnosticTrigger) {
  if(diagnosticTrigger>0 && session.active) {
   val v=web
   if(v==null){error="浏览器尚未创建"}
   else {
    diagnosing=true;diagnosticRequest++
    val requestId=diagnosticRequest
    val page=v.url.orEmpty();val generation=readGeneration;val round=session.round
    fun unavailable() {
     if(!session.accepts(round) || requestId!=diagnosticRequest || generation!=readGeneration)return
     diagnosing=false
     diagnosticTelemetry=session.freeze(androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName?:"unknown",runCatching{androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)}.getOrDefault(false),documentStart.state(v),null,emptyList(),probe=MfaProbe.unknown())
     diagnosticPage=if(allowed(page))SchoolDiagnostics.location(page) else "[地址不可用]";diagnosticEvents=if(allowed(page))events.toList() else emptyList();diagnosticResult="null";receiptId=java.util.UUID.randomUUID().toString();sendStatus=null
     diagnostic="页面结构 unavailable（不可用）；表格/框架/模型/完成状态未知，未伪造正常0。仅保留本次原生网络与证书事件。\n"+diagnosticTelemetry.toString()+"\n"+session.comparison()
    }
    if(!allowed(page)){unavailable();return@LaunchedEffect}
    v.postDelayed({if(diagnosing && requestId==diagnosticRequest && generation==readGeneration)unavailable()},8000)
    val safeOrigins=kotlinx.serialization.json.JsonArray(hosts.map{kotlinx.serialization.json.JsonPrimitive("https://$it")}).toString()
    v.evaluateJavascript("(function(){if("+safeOrigins+".indexOf(location.origin)<0)return '{}';return "+AuthResourceDiagnostics.diagnosticScript+";})()"){result->
     if(!diagnosing || requestId!=diagnosticRequest || !session.accepts(round))return@evaluateJavascript
     diagnosing=false
     if(web!==v || readGeneration!=generation || v.url!=page){error="页面已变化，请重新生成诊断"}
     else runCatching{
      val types=AuthResourceDiagnostics.jsTypes(result).split(",")
      diagnosticTelemetry=session.freeze(androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName?:"unknown",runCatching{androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)}.getOrDefault(false),documentStart.state(v),AuthDocumentStart.observed(result),types,kotlinx.serialization.json.Json.parseToJsonElement(kotlinx.serialization.json.Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject["capabilities"]?.jsonObject,MfaProbe.decode(result))
      SchoolDiagnostics.report(schoolName,top.ccbase.campus.BuildConfig.VERSION_NAME,page,SchoolDiagnostics.signals(result)+"\n"+MfaProbe.preview(MfaProbe.decode(result)),events.toList())+"\n后台固定诊断v2：\n"+diagnosticTelemetry.toString()+"\n"+session.comparison()
     }.onSuccess{diagnosticPage=SchoolDiagnostics.location(page);diagnosticEvents=events.toList();diagnosticResult=result;receiptId=java.util.UUID.randomUUID().toString();sendStatus=null;diagnostic=it}.onFailure{unavailable()}
    }
   }
  }
 }
 BackHandler {if(toolbarProgress<1f){toolbarGesture?.restore();toolbarProgress=1f} else if(windows?.back()==true) Unit else if(web?.canGoBack()==true)web?.goBack() else close()}
 DisposableEffect(Unit){onDispose{session.stop();documentStart.stop();events.clear();windows?.dispose();rootWeb?.let{hosts.forEach{SchoolCookie.clear(it);WebStorage.getInstance().deleteOrigin("https://$it")};it.stopLoading();it.clearCache(true);it.destroy()};web=null;rootWeb=null;windows=null}}
 androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides top.ccbase.campus.ui.theme.C.txt) {
 Column(Modifier.fillMaxSize()){
  Box(Modifier.fillMaxWidth().height(48.dp)) {
  if(toolbarVisible)Row(Modifier.fillMaxWidth().height(48.dp).graphicsLayer { translationY=-(1f-toolbarProgress)*48.dp.toPx();alpha=toolbarProgress }) {
   TextButton(onClick={if(windows?.back()!=true){if(web?.canGoBack()==true)web?.goBack() else error="已经是第一页；信息中可关闭"}} ,modifier=Modifier.weight(1f)){Text("返回",maxLines=1)}
   TextButton(onClick={web?.reload();error="正在刷新官方页面"},modifier=Modifier.weight(1f)){Text("刷新",maxLines=1)}
   TextButton(enabled=diagnosticEnabled && !diagnosing,onClick={diagnosticTrigger++},modifier=Modifier.weight(1f)){Text("诊断",maxLines=1)}
   TextButton(onClick={showInfo=true},modifier=Modifier.weight(1f)){Text("信息",maxLines=1)}
  }
  }
  Box(Modifier.fillMaxSize()) {
  Column(Modifier.fillMaxSize()) {
  AndroidView(modifier=Modifier.weight(1f).fillMaxWidth().testTag("schoolWebViewport"),factory={ctx->WebView(ctx).apply{
   web=this;rootWeb=this;AuthBrowserWindows.configure(this)
   CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
   webViewClient=object:WebViewClient(){
    override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse? {
     if(r.isForMainFrame && !allowed(r.url.toString()))return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))
     return null
    }
    override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean {val blocked=!allowed(r.url.toString());if(blocked){session.navigate("blocked");event("跳转被拦截",r.url.toString());v.stopLoading();error="已阻止非认证白名单跳转。需要核实官方回跳链路后接入。"};return blocked}
    override fun onPageStarted(v:WebView,u:String?,icon:android.graphics.Bitmap?){windows?.navigated(v,u.orEmpty());loading=true;readGeneration++;reading=false;diagnosticRequest++;diagnosing=false;diagnostic=null;event("页面开始",u.orEmpty());session.navigate(session.stage(u.orEmpty()));extracted=null;if(!allowed(u.orEmpty())){v.stopLoading();error="已停止非白名单页面"}}
    override fun onPageFinished(v:WebView,u:String?){loading=false;session.navigate("finished")}
    override fun onReceivedSslError(v:WebView,h:SslErrorHandler,e:SslError){h.cancel();session.navigate("certificate");diagnosticTrigger++;event("证书错误");loading=false;error="学校证书校验失败，登录已停止"}
    override fun onReceivedHttpError(v:WebView,r:WebResourceRequest,response:WebResourceResponse){if(allowed(v.url.orEmpty()))session.resource("http",if(r.isForMainFrame)"document" else AuthResourceDiagnostics.category(r.url.toString()),response.statusCode);if(r.isForMainFrame)diagnosticTrigger++}
    override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(allowed(v.url.orEmpty()))session.resource("network",if(r.isForMainFrame)"document" else AuthResourceDiagnostics.category(r.url.toString()),e.errorCode);if(r.isForMainFrame){event("主页面加载错误类型 ${e.errorCode}");loading=false;diagnosticTrigger++;error="官方登录页加载失败，请检查网络后刷新"}}
   };windows=AuthBrowserWindows(this,::allowed,{v->web=v;readGeneration++;reading=false;diagnosticRequest++;diagnosing=false;diagnostic=null;extracted=null;loading=v.progress<100},{kind->event(kind)},{v->documentStart.install(v);toolbarGesture=BrowserToolbarGesture(v,{showInfo || diagnostic!=null || extracted!=null || confirmClear}){toolbarProgress=it};v.setOnTouchListener(toolbarGesture)},{showInfo || diagnostic!=null || extracted!=null || confirmClear});loadUrl(entry)
  }})
  }
  if(loading && toolbarVisible)LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.takeIf{toolbarVisible}?.let { message -> Surface(modifier=Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth(),color=MaterialTheme.colorScheme.errorContainer) { Text(message,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp)) } }
  }
 }
 }
 if(showInfo)AlertDialog(onDismissRequest={showInfo=false},title={Text("$schoolName · 登录与安全")},text={androidx.compose.foundation.lazy.LazyColumn {
 item { Column {
  TextButton(enabled=!loading && !reading,onClick={
   val v=web
   val url=v?.url.orEmpty()
   if(v==null || !allowed(url)){error="当前页面不在允许HTTPS域，原课表未改变"}
   else {
    showInfo=false;reading=true;error="正在读取课表";extracted=null
    val generation=readGeneration
    v.postDelayed({if(reading && generation==readGeneration){reading=false;error="读取超时，原课表未改变";diagnosticTrigger++}},10000)
    event("读取开始")
    fun handle(result:String) {

     if(!reading || web!==v)return
     reading=false
     if(generation!=readGeneration || v.url!=url || !allowed(v.url.orEmpty())){error="页面已变化，请重新读取；原课表未改变"}
     else runCatching{VisibleTimetableExtractor.decode(result)}.onSuccess{extracted=it}.onFailure{error=it.message ?: "读取失败，原课表未改变";event("读取失败");diagnosticTrigger++}
    }
   if(schoolName=="东北农业大学") {
    if(!VisibleTimetableExtractor.allowed(v.url ?: "",NeauImport.courseHosts)) {
     reading=false;error="尚未进入教务系统。请在VPN中打开本科学生教务，再点击读取课表。";diagnosticTrigger++
    } else {
     val request=java.util.UUID.randomUUID().toString().replace("-","")
     v.evaluateJavascript(NeauImport.script(request),null)
     fun poll() {
      if(!reading || generation!=readGeneration || web!==v)return
      v.evaluateJavascript("(function(){var r=window.__campusNeau;return r&&r.id==='${request}'&&r.done?r.result:null;})()"){result->
       if(result=="null")v.postDelayed({poll()},200) else handle(result)
      }
     }
     v.postDelayed({poll()},200)
    }
   } else v.evaluateJavascript(VisibleTimetableExtractor.script(hosts)){result->handle(result)}
   }
   }){Text(if(reading) "读取中…" else "尝试读取课表",color=top.ccbase.campus.ui.theme.C.txt)}
  TextButton(onClick={
   if(diagnosticEnabled){showInfo=false;error="本次诊断已停止，刷新不会重启";session.stop();documentStart.stop();events.clear();diagnosticRequest++;diagnosing=false;diagnostic=null;diagnosticTelemetry=null;diagnosticResult=null;diagnosticEvents=emptyList();sendStatus=null;sending=false;diagnosticEnabled=false}
   else {session.start();diagnosticEnabled=true;documentStart.enabled=true;windows?.prepareDiagnostics();web?.reload();error="本次诊断已开启并刷新官方页面；失败生成后请预览同意发送。"}
  }){Text(if(diagnosticEnabled) "停止本次登录诊断" else "开启本次登录诊断（刷新）")}
  TextButton(enabled=diagnosticEnabled && !diagnosing,onClick={showInfo=false;diagnosticTrigger++}){Text(if(diagnosing) "生成中…" else "生成脱敏诊断")}

 } }
 item {
  Text("仅允许已核实HTTPS域。读取仅尝试已知树维或URP结构，不读取密码、Cookie或完整网页；失败保留原课表。"+(if(top.ccbase.campus.BuildConfig.SCHOOL_TEST) "测试版打开此学校登录页默认开启本次诊断，仅在本页采集脱敏结构与固定错误类型，不在App后台常驻。可停止本次诊断，刷新不会重启，重新打开此页默认开启。" else "正式版默认关闭本次诊断，可主动开启。")+"诊断失败时生成本机预览，只有预览后主动同意才上传给开发者，保存30天，不自动上传。关闭页清理诊断及关联和认证会话。",modifier=Modifier.padding(12.dp))
 }
 item { Column { TextButton(onClick={confirmClear=true}){Text("清除登录")};TextButton(onClick=close){Text("关闭学校页")} } }
 }},confirmButton={TextButton(onClick={showInfo=false}){Text("返回网页")}})
 diagnostic?.let { report ->
  AlertDialog(onDismissRequest={diagnostic=null},title={Text("预览脱敏诊断")},text={androidx.compose.foundation.lazy.LazyColumn{item{Text(report)};item{Text("读取失败仅自动诊断，不自动上传。主动发送后开发者后台保存30天。")};item{TextButton(enabled=!sending && sendStatus?.startsWith("已发送")!=true,onClick={
    session.consent();val sendTelemetry=session.approved() ?: return@TextButton
    sending=true
    val sendRound=session.round
    val sendId=receiptId;val sendPage=diagnosticPage;val sendResult=diagnosticResult;val sendEvents=diagnosticEvents
    scope.launch {try{val receipt=withContext(Dispatchers.IO){SchoolDiagnosticSender.send(sendId,when(schoolName){"安徽工业大学"->"ahut";"东北农业大学"->"neau";else->"aust"},sendPage,sendResult ?: error("诊断缺失"),sendEvents,sendTelemetry)};if(session.accepts(sendRound)){session.sent(receipt);sendStatus="已发送，编号：$receipt"}}catch(e:Exception){if(session.accepts(sendRound))sendStatus=when {e.message?.startsWith("HTTP_")==true->"发送失败（${e.message}），可重试或分享";e is java.net.SocketTimeoutException->"网络超时，可重试或分享";else->"发送失败（网络或回执异常），可重试或分享"}}finally{if(session.accepts(sendRound))sending=false}}
   }){Text(if(sending) "发送中…" else "同意并发送给开发者")}};sendStatus?.let{status->item{Text(status)}}}},confirmButton={TextButton(onClick={
   runCatching {context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply{type="text/plain";putExtra(android.content.Intent.EXTRA_TEXT,report)},"分享脱敏诊断"))}.onFailure{error="无法打开分享，请使用复制诊断"}
  }){Text("系统分享")}},dismissButton={TextButton(onClick={val cm=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;cm.setPrimaryClip(android.content.ClipData.newPlainText("脱敏诊断",report));diagnostic=null;error="诊断已复制，可粘贴发送"}){Text("复制诊断")}})
 }
 extracted?.let { payload ->
  AlertDialog(onDismissRequest={extracted=null},title={Text("课表预览",color=top.ccbase.campus.ui.theme.C.txt)},text={
   androidx.compose.foundation.lazy.LazyColumn { item { Text(VisibleTimetableExtractor.preview(payload),color=top.ccbase.campus.ui.theme.C.txt) };item {Text(if(onTimetable==null) "当前页面未连接本机入库，仅预览。" else "确认后交给父组件本机校验入库，不上传。",color=top.ccbase.campus.ui.theme.C.txt)} }
  },confirmButton={TextButton(enabled=onTimetable!=null,onClick={
   runCatching{onTimetable?.invoke(payload)}.onSuccess{extracted=null}.onFailure{error="本机导入失败，原课表未改变";extracted=null}
  }){Text("确认本机导入",color=top.ccbase.campus.ui.theme.C.txt)}},dismissButton={TextButton(onClick={extracted=null}){Text("取消",color=top.ccbase.campus.ui.theme.C.txt)}})
 }
 if(confirmClear)AlertDialog(onDismissRequest={confirmClear=false},title={Text("清除${schoolName}认证会话？")},text={Text("仅清理该认证域，不影响CUPK课表和登录。")},confirmButton={TextButton(onClick={hosts.forEach{SchoolCookie.clear(it);WebStorage.getInstance().deleteOrigin("https://$it")};windows?.dispose();rootWeb?.clearCache(true);rootWeb?.loadUrl(entry);confirmClear=false}){Text("清除")}},dismissButton={TextButton(onClick={confirmClear=false}){Text("取消")}})
}
