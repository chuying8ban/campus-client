package top.ccbase.campus.school
import kotlinx.serialization.json.*
/** Strict UTF-8 CSV with explicit times; no school-specific bell-time guesses. */
object SchoolCsv {
 const val HEADER="课程,星期,开始时间,结束时间,周次,地点,教师"
 fun parse(raw:String):String {
  require(raw.length<=524288){"文件超过512KB"}
  val lines=raw.removePrefix("\uFEFF").lineSequence().filter{it.isNotBlank()}.toList()
  require(lines.firstOrNull()==HEADER){"请使用提供的CSV表头"};require(lines.size in 2..1001){"需有1到1000条课程"}
  return buildJsonArray{lines.drop(1).forEachIndexed{i,line->
   val v=line.split(',');require(v.size==7){"第${i+2}行须为7列，单元格不能含英文逗号"}
   val day=v[1].trim().toIntOrNull();require(day in 1..7 && v[0].isNotBlank()){"第${i+2}行名称或星期错误"}
   val start=java.time.LocalTime.parse(v[2].trim());val end=java.time.LocalTime.parse(v[3].trim());require(start<end){"第${i+2}行结束须晚于开始"}
   val weeks=TimetableContract.weeks(v[4].replace(';',','),30)
   add(buildJsonObject{put("courseName",v[0].trim());put("weekday",day!!);put("startUnit",1);put("endUnit",1);put("startTime",start.toString());put("endTime",end.toString());put("weekIndexes",buildJsonArray{weeks.forEach{add(JsonPrimitive(it))}});put("room",v[5].trim());put("teachers",buildJsonArray{add(JsonPrimitive(v[6].trim()))})})
  }}.toString()
 }
}
