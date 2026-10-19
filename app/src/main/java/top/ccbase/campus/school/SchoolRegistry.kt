package top.ccbase.campus.school

import java.net.URI
import top.ccbase.campus.net.EamsClient
import top.ccbase.campus.net.SchoolImportPolicy
import top.ccbase.campus.domain.activitiesFrom

/** Explicit reviewed configuration; never infer trust from an arbitrary user URL. */
data class SchoolProfile(
 val id: String,
 val name: String,
 val level: String,
 val origin: String,
 val adapterId: String,
 val loginPath: String,
 val timetablePath: String,
 /**
  * 登录链路上必须信任的**其它**域名（2026-10-19 融合门户改造）。
  *
  * 统一身份认证跳转到 `cas.cupk.edu.cn`，不放行这里 WebView 会直接拦下、
  * 报"阻止加载非官方教务地址"。只按域名精确匹配、仍然要求 https/443/无 userinfo，
  * **不等于**放宽导入白名单（见 SchoolImportPolicy，那边仍然只认 eams）。
  */
 val extraTrustedHosts: Set<String> = emptySet(),
 /** 备用入口（教务直登）。主入口出问题时可切回来，不带 query。 */
 val backupLoginPath: String? = null,
) {
 fun allows(url: String): Boolean = runCatching {
  val u=URI(url);val trusted=URI(origin)
  val h=u.host?.lowercase()
  val okHost = h==trusted.host?.lowercase() || (h!=null && extraTrustedHosts.any { it.lowercase()==h })
  u.isAbsolute && u.scheme?.lowercase()=="https" && okHost && u.rawUserInfo==null && (u.port==-1 || u.port==443)
 }.getOrDefault(false)
 val host: String get() = URI(origin).host
 /** 主登录入口的完整 URL */
 val loginUrl: String get() = origin + loginPath
 /** 备用入口的完整 URL（没有备用就回主入口） */
 val backupLoginUrl: String get() = origin + (backupLoginPath ?: loginPath)
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
 // 2026-10-19 用户拍板：主登录改走**融合门户的统一身份认证**（CAS → eams SSO），
 // 教务直登留作备用（`backupLoginPath`）。SSO 成功后 eams 自己下发 SESSION，
 // 和直登拿到的是同一种会话，所以"读课表"那一层没动。
 val cupk=SchoolProfile(
  "cupk-undergraduate","中国石油大学（北京）克拉玛依校区","本科",
  EamsClient.DEFAULT_BASE,ShuweiStudentAdapter.id,
  "/student/sso/login?targetUrl=/student/home",
  EamsClient.COURSE_TABLE_PATH,
  extraTrustedHosts = setOf("cas.cupk.edu.cn"),
  backupLoginPath = "/student/login",
 )
 val schools=listOf(cupk)
 fun search(query: String)=schools.filter{query.isBlank() || (it.name+it.id+"CUPK 中石大 克校").contains(query.trim(),ignoreCase=true)}
 fun adapter(profile: SchoolProfile): TimetableAdapter {
  require(schools.any{it==profile}) { "学校尚未适配" }
  require(profile.adapterId==ShuweiStudentAdapter.id)
  return ShuweiStudentAdapter
 }
}
