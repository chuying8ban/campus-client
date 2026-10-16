package top.ccbase.campus.school

import kotlinx.serialization.json.*

/** In-memory page-scoped round; test builds start on page creation, never on refresh. */
internal class LoginDiagnosticSession(defaultEnabled:Boolean=false, private val clock:()->Long = {android.os.SystemClock.elapsedRealtime()}) {
 var active=false;private set
 var round=0;private set
 private var started=0L
 private val resources=mutableListOf<JsonObject>()
 private val navigation=mutableListOf<JsonObject>()
 private var frozen:JsonObject?=null
 private var agreed=false
 private var previous:String?=null
 var sessionId=java.util.UUID.randomUUID().toString();private set
 private var previousReceipt:String?=null
 init { if(defaultEnabled)start() }
 fun sent(receipt:String){check(active && agreed);require(java.util.UUID.fromString(receipt).toString()==receipt);previousReceipt=receipt}
 fun clearComparison(){previous=null;previousReceipt=null}
 fun start(){stop();round++;active=true;sessionId=java.util.UUID.randomUUID().toString();started=clock()}
 fun stop(){previousReceipt=null;previous=null;active=false;round++;resources.clear();navigation.clear();frozen=null;agreed=false}
 fun accepts(r:Int)=active && r==round
 private fun ms()=(clock()-started).coerceIn(0,3600000)
 fun resource(kind:String,category:String,code:Int){
  if(!active || resources.size>=16)return
  if(kind !in setOf("http","network") || category !in setOf("document","script","style","font","image","other"))return
  if(if(kind=="http")code !in 400..599 else code !in -16..-1)return
  resources.add(buildJsonObject{put("kind",kind);put("category",category);put("code",code);put("ms",ms())})
 }
 fun navigate(stage:String){if(active && navigation.size<16 && stage in setOf("authentication","teaching","other","blocked","certificate","finished","reauth_check","reauth_submit"))navigation.add(buildJsonObject{put("stage",stage);put("ms",ms())})}
 fun stage(url:String):String=MfaProbe.stage(url) ?: runCatching{val p=java.net.URI(url).path.orEmpty().lowercase();when{p.contains("auth")||p.contains("login")->"authentication";p.contains("eams")||p.contains("course")||p.contains("student")->"teaching";else->"other"}}.getOrDefault("other")
 fun errorCount()=resources.size
 fun freeze(webView:String,supported:Boolean,registration:String,executed:Boolean?,types:List<String>, capabilities:JsonObject?=null,probe:JsonObject?=null):JsonObject {
  check(active)
  val allowed=setOf("TypeError","ReferenceError","SyntaxError","RangeError","SecurityError","NotSupportedError","OtherError","UnhandledRejection")
  frozen=buildJsonObject {
   if(probe!=null){MfaProbe.validate(probe);put("probe",probe)}
   put("webView",webView.takeIf{it.matches(Regex("(?:[0-9]{1,4}\\.){3}[0-9]{1,4}"))}?:"unknown")
   put("documentStart",supported);put("registration",registration);put("executed",executed?.let{JsonPrimitive(it)}?:JsonNull)
   put("sessionId",sessionId);put("previousReceipt",previousReceipt?.let{JsonPrimitive(it)}?:JsonNull)
   put("capabilities",capabilities ?: buildJsonObject{listOf("Promise","fetch","WebAssembly","credentials").forEach{put(it,JsonNull)}})
   put("jsTypes",JsonArray(types.filter{it in allowed}.distinct().take(8).map{JsonPrimitive(it)}))
   put("resources",JsonArray(resources.toList()));put("navigation",JsonArray(navigation.toList()));put("elapsedMs",ms())
  };agreed=false;return frozen!!
 }
 fun consent(){if(active && frozen!=null)agreed=true}
 fun approved():JsonObject?=if(active && agreed)frozen else null
 fun comparison():String {
  val now="资源错误 ${resources.size} 项；阶段 ${navigation.map{it["stage"]!!.jsonPrimitive.content}.distinct().joinToString("/")}；执行标记 ${frozen?.get("executed")?.takeUnless{it==JsonNull}?:"unknown（未知）"}"
  val answer="本次：$now\n上次：${previous?:"尚无同手机对照"}\n无错误不代表登录修好；观察器未执行、页面未完成或iframe内失败均可能漏采。认证阶段失败先检查系统WebView更新/网络；教务阶段失败请进入官方课表页再读取；other资源403不能判定认证失败。"
  previous=now;return answer
 }
}
