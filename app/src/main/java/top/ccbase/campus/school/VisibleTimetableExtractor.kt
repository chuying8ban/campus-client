package top.ccbase.campus.school

import java.net.URI
import kotlinx.serialization.json.*
import top.ccbase.campus.net.SchoolImportPolicy

/** Explicit, local-only read of the known Tree-view activity model. No DOM scraping. */
object VisibleTimetableExtractor {
    fun allowed(url: String, hosts: Set<String>): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host in hosts && u.rawUserInfo == null && (u.port == -1 || u.port == 443)
    }.getOrDefault(false)

    fun script(hosts: Set<String>): String {
        val origins = JsonArray(hosts.sorted().map { JsonPrimitive("https://$it") }).toString()
        return """
        (function(){
          if($origins.indexOf(location.origin)<0) return '[]';
          try {
            var models = typeof studentTableVms !== 'undefined' ? studentTableVms : window.studentTableVms;
            var rows = models && models[0] && models[0].activities;
            if(!Array.isArray(rows) || rows.length>1000) return '[]';
            var fields=['courseName','lessonName','weekday','startUnit','endUnit','weekIndexes','room','teachers','startTime','endTime'];
            return JSON.stringify(rows.map(function(row){
              var out={};
              fields.forEach(function(key){
                var v=row && row[key];
                if(typeof v==='string' || typeof v==='number') out[key]=v;
                else if(Array.isArray(v) && v.length<=100 && v.every(function(x){return typeof x==='string'||typeof x==='number';})) out[key]=v;
              });
              return out;
            }));
          } catch(e) { return '[]'; }
        })()
        """.trimIndent()
    }

    /** Reject the whole batch on incomplete/ambiguous scheduling data; never invent weeks/times. */
    fun decode(result: String): String {
        require(result.length <= 512_000) { "课表数据过大，原课表未改变" }
        val wrapped = Json.parseToJsonElement(result) as? JsonPrimitive
            ?: error("未识别课表数据，原课表未改变")
        require(wrapped.isString) { "未识别课表数据，原课表未改变" }
        val original = Json.parseToJsonElement(wrapped.content) as? JsonArray
            ?: error("未识别课表数据，原课表未改变")
        require(original.isNotEmpty() && original.size <= 1000) { "当前页面没有可识别的树维课表，原课表未改变" }
        val clean = SchoolImportPolicy.activityAllowlist(original.toString())
        val rows = Json.parseToJsonElement(clean).jsonArray
        require(rows.size == original.size) { "课程字段不完整，原课表未改变" }
        rows.forEach { element ->
            val row = element.jsonObject
            fun integer(key: String) = (row[key] as? JsonPrimitive)?.intOrNull
            val day = integer("weekday")
            val start = integer("startUnit")
            val end = integer("endUnit")
            val weeks = row["weekIndexes"] as? JsonArray
            require(day != null && day in 1..7 && start != null && start > 0 && end != null && end >= start &&
                weeks != null && weeks.isNotEmpty() && weeks.all { (it as? JsonPrimitive)?.intOrNull?.let { n -> n > 0 } == true }) {
                "缺少明确星期、节次或周次，原课表未改变"
            }
        }
        return clean
    }

    fun preview(clean: String): String = Json.parseToJsonElement(clean).jsonArray.joinToString("\n") {
        val row = it.jsonObject
        val name = (row["courseName"] ?: row["lessonName"])!!.jsonPrimitive.content
        "$name · 星期${row["weekday"]} · ${row["startUnit"]}-${row["endUnit"]}节 · 周次${row["weekIndexes"]}"
    }
}
