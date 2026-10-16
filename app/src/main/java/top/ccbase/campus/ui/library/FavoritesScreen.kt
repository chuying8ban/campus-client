package top.ccbase.campus.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.util.Links

@Composable
fun FavoritesScreen(db: CampusDb) {
 val ctx=LocalContext.current
 val scope=rememberCoroutineScope()
 var entries by remember { mutableStateOf<List<Library.LibPick>>(emptyList()) }
 var loading by remember { mutableStateOf(true) }
 var query by remember { mutableStateOf("") }
 var kind by remember { mutableStateOf("") }
 var remove by remember { mutableStateOf<Library.LibPick?>(null) }
 var message by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var browser by remember { mutableStateOf<String?>(null) }
 if(browser!=null){ResourceBrowser(browser!!){browser=null};return}
 LaunchedEffect(db) {try { entries=withContext(Dispatchers.IO){Library.picks(db)} }catch(e:Exception){message="收藏读取失败，请重新进入重试"}finally{loading=false} }
 val filtered=entries.filter{ (kind.isEmpty()||it.kind==kind) && (query.isBlank()||listOf(it.title,it.source,it.note,it.course).any{s->s.contains(query,true)}) }.distinctBy{it.url}
 Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
  Text("我的收藏",color=C.txt,style=MaterialTheme.typography.headlineSmall)
  OutlinedTextField(query,{query=it},label={Text("搜索收藏标题、来源或内容")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Row { listOf("" to "全部","video" to "视频","doc" to "文档","practice" to "练习").forEach{(k,t)->TextButton(onClick={kind=k}){Text(t,color=if(k==kind)C.cyan else C.txt3)}} }
  if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(!loading && filtered.isEmpty()) Text(if(entries.isEmpty())"还没有收藏。在全网搜索或资源目录中收藏资料，即可在这里查看。" else "没有匹配的收藏，试试其他关键词或类型。",color=C.txt2)
  LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp)) {
   items(filtered,key={it.url}){item->
    Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
     TextButton(onClick={browser=item.url},contentPadding=PaddingValues(0.dp)){Text(item.title,color=C.cyan,style=MaterialTheme.typography.titleMedium)}
     Text(Links.hostOf(item.url),color=C.txt3)
     if(item.note.isNotBlank())Text(item.note,color=C.txt2)
     TextButton(onClick={remove=item},enabled=!busy){Text("取消收藏")}
     HorizontalDivider(color=C.line)
    }
   }
  }
 }
 remove?.let{item->AlertDialog(onDismissRequest={if(!busy)remove=null},title={Text("取消收藏？")},text={Text(item.title+"\n仅从你的收藏中移除，不影响原网页。")},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{try{withContext(Dispatchers.IO){Library.unpick(db,setOf(item.url))};entries=withContext(Dispatchers.IO){Library.picks(db)};remove=null;message="已取消收藏"}catch(e:Exception){message="取消失败，请重试"}finally{busy=false}}}){Text("取消收藏")}},dismissButton={TextButton(enabled=!busy,onClick={remove=null}){Text("保留")}})}
 message?.let{AlertDialog(onDismissRequest={message=null},title={Text("收藏")},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})}
}
