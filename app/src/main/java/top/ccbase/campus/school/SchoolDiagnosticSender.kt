package top.ccbase.campus.school
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
/** Deliberately independent of TokenStore and WebView CookieManager. */
object SchoolDiagnosticSender {
 fun payload(id:String,school:String,page:String,result:String,events:List<String>,telemetry:JsonObject?=null):String {
  java.util.UUID.fromString(id);require(school in listOf("aust","ahut","neau"))
  if(telemetry!=null) validateTelemetry(telemetry)
  val values=runCatching {
   SchoolDiagnostics.signals(result)
   Json.parseToJsonElement(Json.parseToJsonElement(result).jsonPrimitive.content).jsonObject
  }.getOrElse { require(telemetry!=null){"Structure unavailable requires v2"};null }
  return buildJsonObject {
   if(telemetry!=null){put("schema",2);put("telemetry",telemetry)}
   put("id",id);put("school",school);put("version",top.ccbase.campus.BuildConfig.VERSION_NAME);put("page",SchoolDiagnostics.location(page))
   if(telemetry!=null)put("structure",if(values==null)"unavailable" else "available")
   listOf("tables","frames","hasTreeModel","ready").forEach{put(it,values?.getValue(it)?:JsonNull)}
   put("events",buildJsonArray{events.takeLast(12).forEach{e-> val kind=e.substringBefore(" · ");val safe=when {kind=="页面开始" || kind=="跳转被拦截"->kind+" · "+SchoolDiagnostics.location(e.substringAfter(" · ",""));kind=="证书错误"->kind;kind.startsWith("主页面加载错误类型 ")->"主页面加载错误";else->"其他事件"};add(JsonPrimitive(safe))}})
  }.toString()
 }
 private fun validateTelemetry(t:JsonObject) {
  val legacy=setOf("webView","documentStart","registration","executed","jsTypes","resources","navigation","elapsedMs")
  val current=legacy+setOf("sessionId","previousReceipt","capabilities")
  require(t.keys==legacy || t.keys==current || t.keys==current+"probe")
  if("probe" in t)MfaProbe.validate(t.getValue("probe").jsonObject)
  require(t["webView"]!!.jsonPrimitive.content.matches(Regex("(?:[0-9]{1,4}\\.){3}[0-9]{1,4}|unknown")))
  require(t["registration"]!!.jsonPrimitive.content in setOf("not_registered","invalid_origins","unsupported_no_observer","registered_document_start","registration_failed_no_observer"))
  require(t["documentStart"]!!.jsonPrimitive.booleanOrNull!=null)
  require(t["executed"]==JsonNull || t["executed"]!!.jsonPrimitive.booleanOrNull!=null)
  if("sessionId" in t){
  java.util.UUID.fromString(t.getValue("sessionId").jsonPrimitive.content)
  if(t["previousReceipt"]!=JsonNull)java.util.UUID.fromString(t.getValue("previousReceipt").jsonPrimitive.content)
  val caps=t.getValue("capabilities").jsonObject
  require(caps.keys==setOf("Promise","fetch","WebAssembly","credentials"))
  require(caps.values.all{it==JsonNull || it.jsonPrimitive.booleanOrNull!=null})
  }
  fun ms(v:JsonElement){require(v.jsonPrimitive.longOrNull in 0L..3600000L)}
  ms(t.getValue("elapsedMs"))
  val types=t.getValue("jsTypes").jsonArray;require(types.size<=8 && types.all{it.jsonPrimitive.content in setOf("TypeError","ReferenceError","SyntaxError","RangeError","SecurityError","NotSupportedError","OtherError","UnhandledRejection")})
  val resources=t.getValue("resources").jsonArray;require(resources.size<=16)
  resources.forEach{v->val r=v.jsonObject;require(r.keys==setOf("kind","category","code","ms"));val kind=r.getValue("kind").jsonPrimitive.content;require(kind in setOf("http","network"));require(r.getValue("category").jsonPrimitive.content in setOf("document","script","style","font","image","other"));require(r.getValue("code").jsonPrimitive.intOrNull in if(kind=="http")400..599 else -16..-1);ms(r.getValue("ms"))}
  val navigation=t.getValue("navigation").jsonArray;require(navigation.size<=16)
  navigation.forEach{v->val n=v.jsonObject;require(n.keys==setOf("stage","ms"));require(n.getValue("stage").jsonPrimitive.content in setOf("authentication","teaching","other","blocked","certificate","finished","reauth_check","reauth_submit"));ms(n.getValue("ms"))}
 }
 fun send(id:String,school:String,page:String,result:String,events:List<String>,telemetry:JsonObject?=null):String {
  val connection=java.net.URL("https://study.ccbase.top/api/v2/school-diagnostics").openConnection() as javax.net.ssl.HttpsURLConnection
  return try {
   connection.requestMethod="POST";connection.connectTimeout=10000;connection.readTimeout=15000;connection.instanceFollowRedirects=false;connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
   connection.outputStream.use{it.write(payload(id,school,page,result,events,telemetry).toByteArray(Charsets.UTF_8))}
   check(connection.responseCode in 200..299){"HTTP_${connection.responseCode}"}
   val text=connection.inputStream.bufferedReader().use{it.readText()};require(text.length<2048)
   Json.parseToJsonElement(text).jsonObject.getValue("receipt").jsonPrimitive.content.also{check(it==id){"回执不匹配"}}
  } finally {connection.disconnect()}
 }
}
