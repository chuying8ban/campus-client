package top.ccbase.campus.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.net.*
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.util.Links

@Composable
fun LearningHub(db: CampusDb) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=14.dp)) {
            listOf("全网搜索", "资源目录", "我的收藏").forEachIndexed { i, text ->
                TextButton(onClick={focus.clearFocus(force=true);keyboard?.hide();mode=i}) { Text(text, color=if(mode==i) C.cyan else C.txt3) }
            }
        }
        Box(Modifier.weight(1f)) {
            when(mode) {
                0 -> WebLearningSearch(db)
                1 -> LibraryScreen(db)
                else -> FavoritesScreen(db)
            }
        }
    }
}

@Composable
internal fun WebLearningSearch(db: CampusDb, search: (suspend (String,String)->ApiResult<LearningSearchResult>)? = null) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusSink = remember { FocusRequester() }
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val api = remember { CampusApi() }
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf("all") }
    val listState = key(kind) { rememberLazyListState() }
    val headerVisible by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 96 } }
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<LearningSearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(db) { saved=withContext(Dispatchers.IO) { Library.pickedUrls(Library.picks(db)) } }
    fun submit(type: String = kind) {
        focus.clearFocus(force=true); focusSink.requestFocus(); keyboard?.hide()
        val q=query.trim()
        if(q.length !in 2..120) { error="请输入2–120字的学习关键词"; return }
        job?.cancel(); generation++; val request=generation
        result=null;error=null;busy=true
        scope.launch { listState.scrollToItem(0) }
        job=scope.launch {
            try {
                val r=withTimeout(30000) { (search ?: { a,b -> api.learningSearch(a,"all") })(q,"all") }
                if(request==generation) when(r) {
                    is ApiResult.Ok -> result=r.value
                    is ApiResult.Err -> error="搜索未成功（${r.code}）：${r.message}"
                }
            } catch(e: TimeoutCancellationException) { if(request==generation)error="搜索超时，请重试" }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(request==generation)error="无法连接搜索服务，请检查网络后重试" }
            finally { if(request==generation)busy=false }
        }
    }
    if(browser != null) { ResourceBrowser(browser!!) {browser=null}; return }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) { focus.clearFocus(force=true); focusSink.requestFocus(); keyboard?.hide() }
    }
    Column(Modifier.fillMaxSize().focusRequester(focusSink).focusable().onGloballyPositioned { containerOrigin = it.positionInRoot() }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val down = event.changes.firstOrNull { !it.pressed && it.previousPressed }
                if (down != null && !searchBounds.contains(down.position + containerOrigin)) {
                    focus.clearFocus(force=true); focusSink.requestFocus(); keyboard?.hide()
                }
            }
        }
    }.padding(horizontal=18.dp)) {
        androidx.compose.animation.AnimatedVisibility(visible=headerVisible) { Column {
        Text("学习库", style=MaterialTheme.typography.headlineSmall,color=C.txt)
        Text("搜索全网学习内容，而不只查现有收藏",color=C.txt3,modifier=Modifier.padding(vertical=6.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(query,{query=it},singleLine=true,label={Text("课程、知识点或学习问题")},modifier=Modifier.weight(1f).onGloballyPositioned { searchBounds = it.boundsInRoot() }.testTag("web-search-input"))
            TextButton(onClick={submit()},enabled=!busy,modifier=Modifier.padding(top=10.dp).testTag("web-search-submit")) {Text(if(busy)"搜索中" else "搜索")}
        }
        } }
        if(!headerVisible) TextButton(onClick={focus.clearFocus(force=true);keyboard?.hide();scope.launch {listState.animateScrollToItem(0)}}){Text("修改搜索") }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("all" to "全部", "video" to "视频课程", "document" to "教程文档", "practice" to "练习题解").forEach { (k,label) ->
                TextButton(modifier=Modifier.testTag("search-kind-$k"),onClick={focus.clearFocus(force=true);focusSink.requestFocus();keyboard?.hide();kind=k;scope.launch {listState.scrollToItem(0)}}) {Text(label,color=if(kind==k)C.cyan else C.txt3)}
            }
        }
        hint?.let { AlertDialog(onDismissRequest={hint=null},title={Text("收藏")},text={Text(it)},confirmButton={TextButton(onClick={hint=null}){Text("知道了")}}) }
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在检索与去重…",color=C.txt3) }
        error?.let { Text(it,color=C.txt2);TextButton(onClick={submit()}){Text("重试")} }
        val r=result?.let { it.copy(items=it.items.filter { row -> kind=="all" || row.kind==kind }) }
        if(r==null && !busy && error==null) {
            Text("试试搜索",color=C.txt2,modifier=Modifier.padding(top=12.dp))
            listOf("高等数学 极限", "C语言 指针", "大学英语 听力", "线性代数 特征值").forEach { example -> TextButton(onClick={query=example;submit()}) {Text(example)} }
            Text("搜索词发送给全网搜索服务。请勿输入学号、密码等个人信息。结果按相关性和来源信号排序，不冒充人工审核。",color=C.txt3,modifier=Modifier.padding(top=12.dp))
        }
        if(r!=null) {
            Text("${r.items.size} 条结果 · ${if(r.cached) "近期缓存" else "全网检索"}${if(r.partial) " · 部分搜索源响应" else ""}",color=C.txt3)
            LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(bottom=24.dp)) {
                if(r.items.isEmpty()) item { Text("没有找到符合筛选条件的资料，请更换关键词或切换全部类型。",color=C.txt2,modifier=Modifier.padding(vertical=16.dp)) }
                items(r.items,key={it.url}) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
                        TextButton(onClick={browser=item.url},contentPadding=PaddingValues(0.dp)) { Text(item.title,color=C.cyan,style=MaterialTheme.typography.titleMedium) }
                        Text(item.source+" · "+when(item.kind){"video"->"视频";"practice"->"练习";else->"文档"},color=C.txt3)
                        Text(item.snippet,color=C.txt2,modifier=Modifier.padding(top=5.dp))
                        if(item.signals.isNotEmpty()) Text(item.signals.joinToString(" · "),color=C.txt3,style=MaterialTheme.typography.bodySmall)
                        TextButton(enabled=item.url !in saved && item.url !in saving,onClick={
                            saving=saving+item.url
                            scope.launch {
                                try {
                                    val n=withContext(Dispatchers.IO) { Library.add(db,listOf(Library.LibPick(url=item.url,title=item.title,kind=if(item.kind=="document")"doc" else item.kind,source=item.source,note=item.snippet))) }
                                    saved=saved+item.url;hint=if(n>0)"已收藏，可在「我的收藏」查看" else "已在收藏中"
                                } catch(e:Exception) {hint="收藏失败，请重试"} finally {saving=saving-item.url}
                            }
                        }) {Text(if(item.url in saved)"已收藏" else if(item.url in saving)"收藏中…" else "收藏到我的收藏")}
                        HorizontalDivider(color=C.line)
                    }
                }
                item { Text(r.notice,color=C.txt3,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
