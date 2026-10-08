package top.ccbase.campus.ui.me

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.ccbase.campus.data.local.*
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.alarm.*

@Composable
fun ScheduleTools(ctx:Context,db:CampusDb,onEdit:()->Unit = {}) {
 var daily by remember { mutableStateOf(false) }
 TextButton(onClick=onEdit) { Text("编辑课程与早晚自习") }
 TextButton(onClick={daily=true}) { Text("每日课表核对提醒") }
 if(daily) {
  var on by remember { mutableStateOf(DailyTimetableCheck.enabled(ctx)) }
  var time by remember { mutableStateOf(DailyTimetableCheck.time(ctx)) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest={daily=false},title={Text("每日课表核对提醒")},text={Column {
   Text("只提醒核对，不自动读取教务。系统省电可能延迟通知；请在授权页面开启通知权限。")
   Row { Text("开启每日提醒");Switch(on,{on=it}) }
   OutlinedTextField(time,{time=it},label={Text("时间 HH:mm")})
   error?.let { Text(it) }
  }},confirmButton={TextButton(onClick={
   if(!Regex("\\d{2}:\\d{2}").matches(time)||runCatching { java.time.LocalTime.parse(time) }.isFailure) error="请输入有效时间，如20:00"
   else { DailyTimetableCheck.configure(ctx,on,time);error=if(on&&!Notify.allowed(ctx)) "设置已保存，但通知权限未开启；请先去授权" else "设置已保存" }
  }) {Text("保存设置")}},dismissButton={TextButton(onClick={daily=false}){Text("关闭")}})
 }
}

@Composable
fun ScheduleEditor(ctx:Context,db:CampusDb,close:()->Unit) {
 val scope=rememberCoroutineScope()
 var plan by remember { mutableStateOf<Seed?>(null) }
 var editing by remember { mutableStateOf(false) }
 var sid by remember { mutableStateOf<Int?>(null) }
 var isStudy by remember { mutableStateOf(false) }
 var name by remember { mutableStateOf("") }
 var teacher by remember { mutableStateOf("") }
 var room by remember { mutableStateOf("") }
 var day by remember { mutableStateOf("1") }
 var start by remember { mutableStateOf("08:45") }
 var end by remember { mutableStateOf("09:15") }
 var weeks by remember { mutableStateOf((1..20).joinToString(",")) }
 var message by remember { mutableStateOf<String?>(null) }
 var deleting by remember { mutableStateOf(false) }
 var saving by remember { mutableStateOf(false) }
 LaunchedEffect(Unit) {plan=ManualSchedule.snapshot(db)}
 fun edit(slot:Slot?=null,study:SelfStudy?=null) {
  sid=slot?.id?:study?.id
  isStudy=study!=null
  val c=plan?.courses?.find {it.id==slot?.course_id}
  name=c?.name?:study?.kind?:"";teacher=c?.teacher.orEmpty();room=slot?.room?:study?.place.orEmpty()
  day=(slot?.weekday?:study?.weekday?:1).toString()
  val times=slot?.time_text?.split('-')
  start=times?.firstOrNull()?:study?.start?:"08:45";end=times?.getOrNull(1)?:study?.end?:"09:15"
  weeks=slot?.weeks?:((slot?.week_from?:1)..(slot?.week_to?:20)).joinToString(",")
  editing=true
 }
 fun commit(p:Seed) { scope.launch {
  saving=true
  try { ManualSchedule.save(db,p);plan=p;editing=false;Rescheduler.request(ctx,"manual_schedule");message="已保存到本机，刷新云端不会覆盖；课前提醒已重新排程" }
  catch(e:Exception) {message="保存失败，未完成：${e.javaClass.simpleName}"}
  saving=false
 } }
 BackHandler { if(editing) editing=false else close() }
 androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
  Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
   Text("编辑课表",style=MaterialTheme.typography.headlineSmall)
   Text("修改仅在本机生效，不更改学校记录，不上传自习。重新导入时会询问如何处理手动课表。")
   if(!editing) {
    TextButton(enabled=plan!=null,onClick={edit()}) {Text("新增课程")}
    TextButton(enabled=plan!=null,onClick={edit();isStudy=true;name="早自习"}) {Text("新增早晚自习")}
    plan?.slots?.forEach {s -> TextButton(onClick={edit(s)}) {Text("${plan?.courses?.find {it.id==s.course_id}?.name} · 周${s.weekday} · ${s.time_text.orEmpty()}")} }
    plan?.selfstudy?.forEach {s -> TextButton(onClick={edit(study=s)}) {Text("${s.kind} · 周${s.weekday} · ${s.start}–${s.end}")} }
   } else {
    OutlinedTextField(name,{name=it},label={Text(if(isStudy) "早自习或晚自习" else "课程名")})
    if(!isStudy) OutlinedTextField(teacher,{teacher=it},label={Text("教师")})
    OutlinedTextField(room,{room=it},label={Text("地点")})
    OutlinedTextField(day,{day=it},label={Text("星期：1周一至7周日")})
    OutlinedTextField(start,{start=it},label={Text("开始 HH:mm")})
    OutlinedTextField(end,{end=it},label={Text("结束 HH:mm")})
    if(!isStudy) OutlinedTextField(weeks,{weeks=it},label={Text("精确周次，英文逗号分隔")})
    else Text("自习按星期每周重复；晚自习沿用节假日减免规则。")
    TextButton(enabled=!saving,onClick={
     val err=ManualSchedule.validate(name,day.toIntOrNull()?:0,start,end,if(isStudy) "1" else weeks)
     if(err!=null) message=err
     else if(isStudy&&name !in listOf("早自习","晚自习")) message="自习名称请选择早自习或晚自习"
     else {
      val p=plan?:return@TextButton
      val id=sid ?: (1_500_000_000+((p.slots.map {it.id}+p.selfstudy.map {it.id}).filter {it in 1_500_000_000..1_599_999_999}.maxOrNull()?.minus(1_500_000_000)?:0)+1)
      if(isStudy) {
       if(p.selfstudy.any {it.id!=sid&&it.weekday==day.toInt()&&it.kind==name}) message="这一天已有同类自习，请编辑已有安排"
       else commit(p.copy(selfstudy=p.selfstudy.filter {it.id!=sid}+SelfStudy(id,day.toInt(),name,start,end,room)))
      } else {
       val existing=p.slots.find {it.id==sid}?.course_id
       val cid=existing?:id
       val ws=weeks.split(',').map {it.trim().toInt()}.distinct().sorted()
       val old=p.courses.find {it.id==cid}?:Course(cid,name)
       commit(p.copy(courses=p.courses.filter {it.id!=cid}+old.copy(name=name,short=name.take(8),teacher=teacher),slots=p.slots.filter {it.id!=sid}+Slot(id,day.toInt(),cid,time_text="$start-$end",room=room,week_from=ws.first(),week_to=ws.last(),weeks=ws.joinToString(","))))
      }
     }
    }) {Text("保存安排")}
    if(sid!=null) TextButton(enabled=!saving,onClick={deleting=true}) {Text("删除这条安排")}
    TextButton(onClick={editing=false}) {Text("取消编辑")}
   }
   TextButton(onClick=close) {Text("完成")}
  } }
 }
 if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("删除这条安排？")},text={Text("只删除这个时段，不删除课程关联任务或打卡。")},confirmButton={TextButton(onClick={deleting=false;plan?.let {p->commit(if(isStudy)p.copy(selfstudy=p.selfstudy.filter {it.id!=sid})else p.copy(slots=p.slots.filter {it.id!=sid}))}}){Text("确认删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
 message?.let {m-> AlertDialog(onDismissRequest={message=null},title={Text("操作结果")},text={Text(m)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}}) }
}
