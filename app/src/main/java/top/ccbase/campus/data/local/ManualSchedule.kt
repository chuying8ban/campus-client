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
 /**
  * 把一个地点同步到**同一门课的所有时段**（2026-10-21 用户要求：
  * 「同一种课支持改一个地点同步到所有相关课程」）。
  *
  * 为什么单独抽成函数而不是在界面里现写 map：这条规则要能被单测钉住 ——
  * 「同步」写错了不会报错，只会悄悄地只改了一处或者改了别人家课的地点，
  * 用户要等到下次上课走错教室才发现。
  */
 fun syncRoom(plan: Seed, courseId: Int?, room: String?): Seed =
  plan.copy(slots = plan.slots.map { if (courseId != null && it.course_id == courseId) it.copy(room = room) else it })

 /**
  * 按作息表对齐已排的自习：时间改成表里那档；**表里关掉的那一档（「无早自习」）清掉**。
  *
  * 为什么必须清：不清的话，今日页还在显示那条早自习，而编辑页已经不显示它了 ——
  * 用户看着今日页有早自习、却在课表编辑里怎么也找不到，删不掉。
  * 返回（新计划, 清掉几条），条数要报给用户，删除不能悄悄发生。
  */
 fun applyStudyWindow(plan: Seed, table: top.ccbase.campus.domain.PeriodTable): Pair<Seed, Int> {
  var removed = 0
  val kept = plan.selfstudy.filter { s ->
   val w = table.study(s.kind)
   val alive = w == null || w.enabled
   if (!alive) removed += 1
   alive
  }
  val retimed = kept.map { s ->
   val w = table.study(s.kind)
   if (w != null && w.enabled) s.copy(start = w.start, end = w.end) else s
  }
  return plan.copy(selfstudy = retimed) to removed
 }

 /** 早晚自习同理：改一次地点，所有同类自习（早/晚）一起改 */
 fun syncStudyPlace(plan: Seed, kind: String, place: String?): Seed =
  plan.copy(selfstudy = plan.selfstudy.map { if (it.kind == kind) it.copy(place = place) else it })

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
