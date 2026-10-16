package top.ccbase.campus.school
import java.net.URI
import kotlinx.serialization.json.*
/** Bounded structural signals only. Never serialize arbitrary DOM or exception text. */
object SchoolDiagnostics {
 fun location(raw:String):String=runCatching {
  val u=URI(raw);require(u.scheme=="https" && u.host!=null)
  val known=setOf("authserver","login","eams","student","for-std","course-table","main","index","enclient")
  val path=u.path.orEmpty().split('/').filter{it.isNotEmpty()}.take(6).joinToString("/"){part->
   val segment=part.substringBefore(';');if(segment in known)segment else when(segment){"login.action","index.html","main.html"->segment;else->"redacted"}
  }
  "https://${u.host}/$path"
 }.getOrDefault("[地址不可用]")
 val script="""(function(){try{return JSON.stringify({tables:Math.min(document.querySelectorAll('table').length,100),frames:Math.min(document.querySelectorAll('iframe,frame').length,100),hasTreeModel:(typeof studentTableVms!=='undefined'||typeof window.studentTableVms!=='undefined'),ready:document.readyState==='complete'});}catch(e){return '{}';}})()"""
 fun signals(result:String):String {
  require(result.length<2048)
  val value=Json.parseToJsonElement(result) as? JsonPrimitive ?: error("诊断返回无效")
  val o=Json.parseToJsonElement(value.content).jsonObject
  fun count(k:String):Int {val n=(o[k] as? JsonPrimitive)?.intOrNull ?: error("诊断未完成");require(n in 0..100);return n}
  fun flag(k:String):Boolean=(o[k] as? JsonPrimitive)?.booleanOrNull ?: error("诊断未完成")
  return "表格数量：${count("tables")}\n框架数量：${count("frames")}\n树维数据结构存在：${flag("hasTreeModel")}\n页面加载完成：${flag("ready") }"
 }
 fun report(school:String,version:String,url:String,signal:String,events:List<String>):String=
  "校园助手·脱敏诊断 v1\n学校：$school\n版本：$version\n页面：${location(url)}\n$signal\n最近事件（最多12条）：\n${events.takeLast(12).joinToString("\n")}\n说明：仅页面结构信号与脱敏地址；不含密码、Cookie、输入值、姓名学号、课表内容或完整网页。由用户主动分享，无自动上传。"
}
