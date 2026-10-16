package top.ccbase.campus.school
import kotlinx.serialization.json.*
import java.net.URI
/** Fixed, read-only NEAU probe. Unknown never becomes a successful measurement. */
internal object MfaProbe {
 private val hosts=setOf("authserver-443.webvpn.neau.edu.cn","authserver.neau.edu.cn")
 private val stages=mapOf("/authserver/reAuthCheck/reAuthCheck.do" to "reauth_check","/authserver/reAuthCheck/reAuthSubmit.do" to "reauth_submit")
 fun stage(url:String):String?=runCatching{val u=URI(url);if(u.scheme=="https" && u.host in hosts && u.rawUserInfo==null && u.port in setOf(-1,443))stages[u.path] else null}.getOrNull()
 val script="""(function(){
 function attempt(f){try{return f();}catch(e){return null;}}
 function num(n){return typeof n==='number'&&isFinite(n)&&Math.abs(n)<=100000?Math.round(n*100)/100:null;}
 function size(e){return e?{width:num(e.clientWidth),height:num(e.clientHeight)}:null;}
 var origins=['https://authserver-443.webvpn.neau.edu.cn','https://authserver.neau.edu.cn'];
 if(origins.indexOf(location.origin)<0)return null;
 var paths={'/authserver/reAuthCheck/reAuthCheck.do':'reauth_check','/authserver/reAuthCheck/reAuthSubmit.do':'reauth_submit'};
 var form=attempt(function(){if(typeof document==='undefined'||!document.forms)return null;var found=null;for(var i=0;i<document.forms.length;i++){var f=document.forms[i];var u=new URL(f.action,location.href);if(origins.indexOf(u.origin)>=0&&u.pathname==='/authserver/reAuthCheck/reAuthSubmit.do'){found=f;break;}}if(!found)return {exists:false,rect:null,display:null,visibility:null,opacity:null,intersects:null};var r=attempt(function(){var b=found.getBoundingClientRect();return {x:num(b.x),y:num(b.y),width:num(b.width),height:num(b.height)};});var s=attempt(function(){return getComputedStyle(found);});return {exists:true,rect:r,display:s?['none','block','inline','inline-block','flex','grid','contents','table'].indexOf(s.display)>=0?s.display:'other':null,visibility:s?['visible','hidden','collapse'].indexOf(s.visibility)>=0?s.visibility:'other':null,opacity:s?num(Number(s.opacity)):null,intersects:r&&r.x!==null&&r.y!==null&&r.width!==null&&r.height!==null? r.width>0&&r.height>0&&r.x+r.width>0&&r.y+r.height>0&&r.x<innerWidth&&r.y<innerHeight:null};});
 return {uaClass:attempt(function(){var u=navigator.userAgent;return /; wv\)/.test(u)?'wv':/Chrome\//.test(u)?'chrome':'other';}),secureContext:attempt(function(){return typeof isSecureContext==='boolean'?isSecureContext:null;}),topLevel:attempt(function(){return window===window.top;}),visibility:attempt(function(){return ['visible','hidden','prerender'].indexOf(document.visibilityState)>=0?document.visibilityState:null;}),viewport:attempt(function(){return {width:num(innerWidth),height:num(innerHeight)};}),root:attempt(function(){return size(document.documentElement);}),visualViewport:attempt(function(){return visualViewport?{width:num(visualViewport.width),height:num(visualViewport.height)}:null;}),reAuthParams:attempt(function(){return typeof reAuthParams!=='undefined';}),stage:paths[location.pathname]||null,form:form};
 })()"""
 private val keys=setOf("uaClass","secureContext","topLevel","visibility","viewport","root","visualViewport","reAuthParams","stage","form")
 fun unknown()=buildJsonObject{keys.forEach{put(it,JsonNull)}}
 fun decode(result:String):JsonObject=runCatching{require(result.length<8192);val outer=Json.parseToJsonElement(result);val o=if(outer is JsonPrimitive && outer.isString)Json.parseToJsonElement(outer.content).jsonObject else outer.jsonObject;val p=o["probe"]?.jsonObject?:o;validate(p);p}.getOrElse{unknown()}
 fun validate(p:JsonObject){
  require(p.keys==keys)
  fun flag(v:JsonElement?){require(v==JsonNull || v is JsonPrimitive && !v.isString && v.booleanOrNull!=null)}
  fun choice(v:JsonElement?,values:Set<String>){require(v==JsonNull || v is JsonPrimitive && v.isString && v.content in values)}
  fun number(v:JsonElement?,low:Double=-100000.0,high:Double=100000.0){require(v==JsonNull || v is JsonPrimitive && !v.isString && v.doubleOrNull?.let{it.isFinite() && it in low..high}==true)}
  fun dims(v:JsonElement?,rect:Boolean=false){if(v==JsonNull)return;val d=v as? JsonObject?:error("Invalid probe");require(d.keys==if(rect)setOf("x","y","width","height") else setOf("width","height"));d.forEach{(k,n)->number(n,if(k in setOf("width","height"))0.0 else -100000.0)}}
  choice(p["uaClass"],setOf("wv","chrome","other"));choice(p["visibility"],setOf("visible","hidden","prerender"));choice(p["stage"],stages.values.toSet())
  listOf("secureContext","topLevel","reAuthParams").forEach{flag(p[it])};listOf("viewport","root","visualViewport").forEach{dims(p[it])}
  if(p["form"]!=JsonNull){val f=p.getValue("form").jsonObject;require(f.keys==setOf("exists","rect","display","visibility","opacity","intersects"));flag(f["exists"]);flag(f["intersects"]);dims(f["rect"],true);choice(f["display"],setOf("none","block","inline","inline-block","flex","grid","contents","table","other"));choice(f["visibility"],setOf("visible","hidden","collapse","other"));number(f["opacity"],0.0,1.0)}
 }
 fun preview(p:JsonObject):String {
  val f=p["form"] as? JsonObject
  val finding=when{f==null->"表单状态未知：重新生成并核对是否仍在官方认证页。";f["exists"]==JsonPrimitive(false)->"固定表单未命中：不能作为根因；请等待后重新生成，与系统浏览器同阶段对照。";f["display"]==JsonPrimitive("none")||f["visibility"]==JsonPrimitive("hidden")||f["opacity"]==JsonPrimitive(0)||f["intersects"]==JsonPrimitive(false)->"固定表单存在但可能不可见：核对尺寸与样式并截屏；不自动改CSS。";else->"固定表单存在：不证明MFA已生成或能交互，请核对实际截图。"}
  return "$finding\n固定probe（null=未知，不含UA原文及认证参数内容）：\n$p"
 }
}
