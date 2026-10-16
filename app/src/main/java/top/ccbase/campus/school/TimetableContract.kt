package top.ccbase.campus.school

import java.time.LocalTime
import kotlinx.serialization.json.*

/** Vendor-neutral import contract. Unknown school bell times must not become CUPK times. */
data class SchoolPeriod(val number:Int,val start:String,val end:String)
data class SchoolCalendar(val firstMonday:String,val weekCount:Int,val periods:List<SchoolPeriod>) {
 fun validate() {
  require(java.time.LocalDate.parse(firstMonday).dayOfWeek==java.time.DayOfWeek.MONDAY){"第一周起始日须为周一"}
  require(weekCount in 1..60){"学期周数须在1到60之间"}
  require(periods.isNotEmpty()){"请确认学校节次时间，不能套用其他学校作息"}
  require(periods.map{it.number}.distinct().size==periods.size){"节次编号重复"}
  periods.forEach{require(it.number>0 && LocalTime.parse(it.start)<LocalTime.parse(it.end)){"节次时间不合法"}}
  periods.sortedBy{it.number}.zipWithNext().forEach{(a,b)->require(LocalTime.parse(a.end)<=LocalTime.parse(b.start)){"节次时间重叠或顺序不正确"}}
 }
}
data class NormalizedCourse(val name:String,val weekday:Int,val firstPeriod:Int,val lastPeriod:Int,val weeks:List<Int>,val room:String="",val teacher:String="",val code:String="")
object TimetableContract {
 fun activities(courses:List<NormalizedCourse>,calendar:SchoolCalendar):String {
  calendar.validate();require(courses.isNotEmpty()){"课表为空，原课表未改变"}
  val periods=calendar.periods.associateBy{it.number}
  return buildJsonArray {
   courses.forEach{c ->
    require(c.name.isNotBlank() && c.weekday in 1..7){"课程名称或星期不合法"}
    require(c.firstPeriod>0 && c.lastPeriod>=c.firstPeriod){"课程节次不合法"}
    require((c.firstPeriod..c.lastPeriod).all{periods.containsKey(it)}){"学校节次时间缺失"}
    require(c.weeks.isNotEmpty() && c.weeks.all{it in 1..calendar.weekCount}){"课程周次缺失或超出学期"}
    add(buildJsonObject{
     put("courseName",c.name.trim());put("weekday",c.weekday);put("startUnit",c.firstPeriod);put("endUnit",c.lastPeriod)
     put("weekIndexes",buildJsonArray{c.weeks.distinct().sorted().forEach{add(JsonPrimitive(it))}})
     put("startTime",periods.getValue(c.firstPeriod).start);put("endTime",periods.getValue(c.lastPeriod).end)
     put("room",c.room);put("teachers",buildJsonArray{if(c.teacher.isNotBlank())add(JsonPrimitive(c.teacher))})
     if(c.code.isNotBlank())put("courseCode",c.code)
    })
   }
  }.toString()
 }
 /** Numeric range syntax only. Reject ambiguous wording instead of inventing weeks. */
 fun weeks(text:String,maxWeeks:Int):List<Int> {
  require(maxWeeks in 1..60)
  val s=text.trim().replace("，",",").replace("、",",").replace("周","")
  require(s.isNotEmpty()){"周次不能为空"}
  val out=mutableSetOf<Int>()
  s.split(',').forEach{part->
   val m=Regex("^(\\d+)(?:-(\\d+))?(?:\\((单|双)\\))?$").matchEntire(part.trim())?:throw IllegalArgumentException("无法确认周次，请选择精确周次")
   val a=m.groupValues[1].toInt();val b=m.groupValues[2].toIntOrNull()?:a
   require(a in 1..maxWeeks && b in a..maxWeeks){"周次范围不合法"}
   (a..b).filter{m.groupValues[3].isEmpty() || (it%2==1)==(m.groupValues[3]=="单")}.forEach{out.add(it)}
  }
  require(out.isNotEmpty()){"未得到有效周次"};return out.sorted()
 }
}
