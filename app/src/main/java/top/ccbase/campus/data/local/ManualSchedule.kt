package top.ccbase.campus.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.data.seed.SeedLoader
import kotlinx.coroutines.flow.first

/** 本机课表拥有独立快照；仅保存课表字段，不保存任务或云端身份。 */
object ManualSchedule {
 const val KEY = "manual_schedule_v1"
 suspend fun snapshot(db: CampusDb): Seed = Seed(courses=db.dao().courses().first(),slots=db.dao().slots().first(),selfstudy=db.dao().selfStudy().first())
 suspend fun save(db: CampusDb, plan: Seed) = db.withTransaction {
  db.dao().putCourses(plan.courses)
  db.dao().clearSlots(); db.dao().putSlots(plan.slots)
  db.dao().putSelfStudy(plan.selfstudy)
  db.dao().pruneSelfStudy(plan.selfstudy.map { it.id }.ifEmpty { listOf(-1) })
  db.dao().putMeta(listOf(Meta(KEY,SeedLoader.json.encodeToString(plan))))
 }
 suspend fun overlay(db: CampusDb, remote: Seed): Seed {
  val raw=db.dao().metaGet(KEY) ?: return remote
  val p=SeedLoader.json.decodeFromString<Seed>(raw)
  // 远端任务引用的课程仍保留，避免破坏任务关联。
  return remote.copy(courses=p.courses + remote.courses.filter { r -> p.courses.none { it.id==r.id } },slots=p.slots,selfstudy=p.selfstudy)
 }
 fun validate(name:String,weekday:Int,start:String,end:String,weeks:String): String? {
  if(name.isBlank()) return "请填写名称"
  if(weekday !in 1..7) return "星期须为1至7"
  val a=runCatching { java.time.LocalTime.parse(start) }.getOrNull()
  val b=runCatching { java.time.LocalTime.parse(end) }.getOrNull()
  if(a==null||b==null||!b.isAfter(a)) return "时间须为HH:mm，结束晚于开始"
  val w=weeks.split(',').map { it.trim().toIntOrNull() }
  if(w.isEmpty()||w.any { it==null||it !in 1..30 }) return "周次填1至30的数字，用英文逗号分隔"
  return null
 }
}
