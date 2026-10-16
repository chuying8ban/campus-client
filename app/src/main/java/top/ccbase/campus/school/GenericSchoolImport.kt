package top.ccbase.campus.school

import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.EamsLocalImport

/** Safe local entrypoint for future vendor parsers. Entire batch validates before database writes. */
object GenericSchoolImport {
 suspend fun import(db:CampusDb,courses:List<NormalizedCourse>,calendar:SchoolCalendar,keepManual:Boolean=true):EamsLocalImport.ImportResult {
  val activities=TimetableContract.activities(courses,calendar)
  val result=EamsLocalImport.import(db,activities,keepManual)
  check(result.imported){"没有有效课程，原课表未改变"}
  return result
 }
}
