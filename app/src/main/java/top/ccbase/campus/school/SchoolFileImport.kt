package top.ccbase.campus.school
import androidx.room.withTransaction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.*
/** Preview first, explicit replacement; never touches the production app's separate database. */
@Composable fun SchoolFileImport(db:CampusDb,close:()->Unit) {
 val ctx=LocalContext.current;val scope=rememberCoroutineScope()
 androidx.activity.compose.BackHandler { close() }
 var error by remember{mutableStateOf<String?>(null)};var pending by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};var done by remember{mutableStateOf(false)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
  busy=true;error=null
  try{pending=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(uri)?.use{val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);while(buffer.size()<=524288){val n=it.read(chunk);if(n<0)break;buffer.write(chunk,0,n)};val b=buffer.toByteArray();require(b.size<=524288){"文件超过512KB"};SchoolCsv.parse(b.toString(Charsets.UTF_8))}?:error("无法读取文件")}}
  catch(e:Exception){error=e.message?:"文件导入失败"}finally{busy=false}
 }}
 androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides top.ccbase.campus.ui.theme.C.txt) {
 Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())){
  TextButton(onClick=close,enabled=!busy){Text("返回")};Text("通用课表文件导入",style=MaterialTheme.typography.headlineSmall)
  Text("适用于各学校按本模板整理的课表，不是自动读取教务。仅本机使用、不连接云端。明确时间，不套用克校作息。最多30周。")
  Text("UTF-8 CSV表头：\n${SchoolCsv.HEADER}\n示例：\n高等数学,1,08:00,09:40,1-16(单),教学楼101,示例教师\n散周用分号分隔，如1;3;5。单元格不含英文逗号。")
  Button(onClick={picker.launch(arrayOf("text/*","application/octet-stream"))},enabled=!busy){Text("选择课表CSV")}
  if(busy)CircularProgressIndicator();error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
  if(done)Text("已导入。原测试课表已替换；没有上传服务器。")
 }
 }
 pending?.let{raw->val plan=remember(raw){EamsLocalImport.build(raw)}
  AlertDialog(onDismissRequest={if(!busy)pending=null},title={Text("核对并替换测试课表")},text={Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())){Text("${plan.courses.size}门课程，${plan.slots.size}个时段。将替换本机现有课程与手工安排，并清除原自习，防止套用其他学校安排。请先保留自己的原文件。")
   plan.slots.forEach{s->Text("${plan.courses.first{it.id==s.course_id}.name} · 星期${s.weekday} · ${s.time_text} · 周${s.weeks}")}}},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{try{withContext(Dispatchers.IO){db.withTransaction{EamsLocalImport.import(db,raw,false);db.dao().pruneSelfStudy(listOf(-1))}};pending=null;done=true}catch(e:Exception){error=e.message;pending=null}finally{busy=false}}}){Text("确认替换")}},dismissButton={TextButton(enabled=!busy,onClick={pending=null}){Text("取消")}})
 }
}
