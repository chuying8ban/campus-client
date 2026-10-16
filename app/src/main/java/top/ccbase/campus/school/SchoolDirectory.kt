package top.ccbase.campus.school
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
data class DirectorySchool(val id:String,val name:String,val province:String,val level:String,val entries:List<String>)
@Composable fun SchoolDirectory(pick:(String)->Unit,close:()->Unit) {
 val ctx=LocalContext.current
 val schools=remember {Json.parseToJsonElement(ctx.assets.open("school_directory.json").bufferedReader().use{it.readText()}).jsonArray.map{e->val r=e.jsonObject;DirectorySchool(r.getValue("id").jsonPrimitive.content,r.getValue("name").jsonPrimitive.content,r.getValue("province").jsonPrimitive.content,r.getValue("level").jsonPrimitive.content,r.getValue("entries").jsonArray.map{it.jsonObject.getValue("url").jsonPrimitive.content})}}
 var query by remember{mutableStateOf("")};var selected by remember{mutableStateOf<DirectorySchool?>(null)}
 AlertDialog(onDismissRequest=close,title={Text("搜索学校")},text={Column{
  OutlinedTextField(query,{query=it},label={Text("学校名称／省份／学校代码")},singleLine=true)
  Text("教育部2026名录 · 3196所（不含港澳台）。名录收录不等于支持导入。")
  LazyColumn(Modifier.height(320.dp)){items(schools.filter{query.isBlank()||(it.name+it.province+it.id).contains(query.trim())},key={it.id}){s->TextButton(onClick={when(s.name){"安徽理工大学"->pick("aust");"安徽工业大学"->pick("ahut");"东北农业大学"->pick("neau");else->selected=s}}){Text("${s.name} · ${s.province} · ${s.level}")}}}
  TextButton(onClick={pick("cupk")}){Text("中国石油大学（北京）克拉玛依校区 · 已接入本科课表")}
 }},confirmButton={TextButton(onClick=close){Text("关闭")}})
 selected?.let{s->AlertDialog(onDismissRequest={selected=null},title={Text(s.name)},text={Text(if(s.entries.isEmpty())"教务入口尚未核实，暂不能直接登录读取。" else "上游公开候选入口，尚未经本App核实，不开放凭据登录：\n"+s.entries.joinToString("\n")+"\n来源：拾光开源适配目录（MIT），不表示本App已支持。")},confirmButton={TextButton(onClick={selected=null;pick("other")}){Text("暂用本机课表")}},dismissButton={TextButton(onClick={selected=null}){Text("返回学校列表")}})}
}
