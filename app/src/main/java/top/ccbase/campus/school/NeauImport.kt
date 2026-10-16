package top.ccbase.campus.school

/** URP field contract investigated from ShiGuangSchedule/shiguang_warehouse NEAU_01 (MIT).
 * No upstream bridge, console or guessed class durations are reused. */
object NeauImport {
 const val entry="https://zhjwxs-80.webvpn.neau.edu.cn/"
 val hosts=setOf("webvpn.neau.edu.cn","authserver-443.webvpn.neau.edu.cn","zhjwxs-80.webvpn.neau.edu.cn","zhjwxs-443.webvpn.neau.edu.cn","zhjwxs.neau.edu.cn")
 val courseHosts=setOf("zhjwxs-80.webvpn.neau.edu.cn","zhjwxs-443.webvpn.neau.edu.cn","zhjwxs.neau.edu.cn")
 fun script(request:String):String {
  require(request.matches(Regex("[a-zA-Z0-9]+")))
  val origins=kotlinx.serialization.json.JsonArray(courseHosts.map{kotlinx.serialization.json.JsonPrimitive("https://$it")})
  return """
  (function(){
   window.__campusNeau={id:'$request',done:false,result:'[]'};
   if($origins.indexOf(location.origin)<0){window.__campusNeau.done=true;return;}
   fetch('/student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback',{method:'GET',credentials:'same-origin',redirect:'error'})
   .then(function(r){if(!r.ok)throw Error();return r.text();})
   .then(function(s){if(s.length>512000)throw Error();var d=JSON.parse(s);var map=d.xkxx&&d.xkxx[0];if(!map||!Array.isArray(d.jcsjbs))throw Error();var times={};
    function time(v){v=String(v);if(!/^\d{4}${'$'}/.test(v))throw Error();return v.slice(0,2)+':'+v.slice(2);}
    d.jcsjbs.forEach(function(t){var n=Number(t.jc);if(!Number.isInteger(n)||n<1||times[n])throw Error();times[n]={start:time(t.kssj),end:time(t.jssj)};});
    function weeks(s){if(typeof s!=='string')throw Error();var odd=s.includes('单'),even=s.includes('双');if(odd&&even)throw Error();s=s.replace(/周|\(单\)|\(双\)|（单）|（双）|单|双|\s/g,'');var out=[];s.split(',').forEach(function(p){if(!/^\d+(?:-\d+)?${'$'}/.test(p))throw Error();var a=p.split('-').map(Number),b=a.length===2?a[1]:a[0];if(a[0]<1||b<a[0]||b>30)throw Error();for(var n=a[0];n<=b;n++){if(odd&&n%2===0||even&&n%2!==0)continue;if(out.indexOf(n)<0)out.push(n);}});if(!out.length)throw Error();return out.sort(function(a,b){return a-b;});}
    var rows=[];Object.keys(map).forEach(function(k){var c=map[k];if(!Array.isArray(c.timeAndPlaceList))throw Error();c.timeAndPlaceList.forEach(function(t){var start=Number(t.classSessions),end=start+Number(t.continuingSession)-1;if(!times[start]||!times[end]||end<start)throw Error();rows.push({courseName:c.courseName,teachers:c.attendClassTeacher||'',weekday:Number(t.classDay),startUnit:start,endUnit:end,weekIndexes:weeks(t.weekDescription),room:(t.campusName||'')+(t.teachingBuildingName||'')+(t.classroomName||''),startTime:times[start].start,endTime:times[end].end});});});if(rows.length>1000)throw Error();if(window.__campusNeau.id==='$request')window.__campusNeau={id:'$request',done:true,result:JSON.stringify(rows)};
   }).catch(function(){if(window.__campusNeau.id==='$request')window.__campusNeau={id:'$request',done:true,result:'[]'};});
  })()
  """.trimIndent()
 }
}
