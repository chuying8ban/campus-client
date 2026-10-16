package top.ccbase.campus.school

import java.net.URI
import top.ccbase.campus.net.EamsClient
import top.ccbase.campus.net.SchoolImportPolicy
import top.ccbase.campus.domain.activitiesFrom

/** Explicit reviewed configuration; never infer trust from an arbitrary user URL. */
data class SchoolProfile(val id: String, val name: String, val level: String, val origin: String, val adapterId: String, val loginPath: String, val timetablePath: String) {
 fun allows(url: String): Boolean = runCatching {
  val u=URI(url);val trusted=URI(origin)
  u.isAbsolute && u.scheme?.lowercase()=="https" && u.host?.lowercase()==trusted.host?.lowercase() && u.rawUserInfo==null && (u.port==-1 || u.port==443)
 }.getOrDefault(false)
 val host: String get() = URI(origin).host
}
interface TimetableAdapter {
 val id: String
 val semesterScript: String
 fun fetchScript(path: String): String
 fun printDataPath(semesterId: Int): String
 fun normalizedActivities(raw: String): String
}
object ShuweiStudentAdapter: TimetableAdapter {
 override val id="shuwei-student-print-data"
 override val semesterScript get()=ShuweiExtraction.semesterScript
 override fun fetchScript(path: String)=ShuweiExtraction.fetchScript(path)
 override fun printDataPath(semesterId: Int) = EamsClient.printDataPath(semesterId)
 override fun normalizedActivities(raw: String): String {
  val a=activitiesFrom(raw) ?: throw IllegalArgumentException("未读到课表活动，原课表未改变")
  val clean=SchoolImportPolicy.activityAllowlist(a.toString())
  require(clean!="[]") { "未读到有效课程，原课表未改变" }
  return clean
 }
}
object SchoolRegistry {
 val cupk=SchoolProfile("cupk-undergraduate","中国石油大学（北京）克拉玛依校区","本科",EamsClient.DEFAULT_BASE,ShuweiStudentAdapter.id,"/student/login",EamsClient.COURSE_TABLE_PATH)
 val schools=listOf(cupk)
 fun search(query: String)=schools.filter{query.isBlank() || (it.name+it.id+"CUPK 中石大 克校").contains(query.trim(),ignoreCase=true)}
 fun adapter(profile: SchoolProfile): TimetableAdapter {
  require(schools.any{it==profile}) { "学校尚未适配" }
  require(profile.adapterId==ShuweiStudentAdapter.id)
  return ShuweiStudentAdapter
 }
}
