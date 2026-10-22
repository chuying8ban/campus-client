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

 /**
  * 把一条跨节的课（第 a-b 节）**拆成每节一条**（2026-10-22 用户要求：
  * 「编辑课表那里要每节课都能改，那种一个课占两节课的要分开」）。
  *
  * 为什么必须拆记录而不是只在界面里画成两行：一条 Slot 的 p_start/p_end 只描述整段，
  * 教室、周次、甚至课程本身都是**整段共用的** —— 用户想把第 2 节换成另一门课、
  * 或者只是第 2 节换教室，在一条记录上根本表达不出来。
  *
  * 拆分**只拆节次**：课程、教室、周次、星期全部照抄，所以拆完看不出任何变化，
  * 只是从此每节能各改各的。自定义时间的课（p_start=null）本来就不按节次，返回原样。
  *
  * @return (新计划, 拆出了几条)；0 表示这条本来就只占一节 / 已经被别的课占着
  */
 fun splitSpan(plan: Seed, slotId: Int): Pair<Seed, Int> {
  val s = plan.slots.firstOrNull { it.id == slotId } ?: return plan to 0
  val a = s.p_start ?: return plan to 0
  val b = s.p_end ?: a
  if (b <= a) return plan to 0
  // 同一天里已经被**别的**时段占掉的节次不拆（拆了就是两条课压在同一个格子上）
  val taken = plan.slots.filter { it.id != slotId && it.weekday == s.weekday }
   .mapNotNull { o -> o.p_start?.let { f -> f..(o.p_end ?: f) } }
   .flatten().toSet()
  val units = (a..b).filter { it !in taken }
  if (units.isEmpty()) return plan to 0
  // 新 id 取现有最大值往后数：本机新增走的是 1_500_000_000+ 这段，别和种子/远端撞号
  var next = (plan.slots.map { it.id } + plan.selfstudy.map { it.id }).maxOrNull() ?: 0
  val pieces = units.map { u ->
   next += 1
   s.copy(id = next, p_start = u, p_end = u)
  }
  return plan.copy(slots = plan.slots.filter { it.id != slotId } + pieces) to pieces.size
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
