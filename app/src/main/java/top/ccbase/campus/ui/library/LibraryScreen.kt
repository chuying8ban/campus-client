package top.ccbase.campus.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.remote.RemoteSync
import top.ccbase.campus.util.Links
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.domain.slotTimeLabel
import top.ccbase.campus.domain.weeksLabel
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.StudentError
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.Catalog
import top.ccbase.campus.net.CatalogItem
import top.ccbase.campus.ui.schedule.fmt
import top.ccbase.campus.ui.schedule.weeksText
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.Digits

/**
 * 「学习库」——**独立的一格**（用户拍板，不塞进「学习」页里）。
 *
 * 它是公共资源目录：所有同学探过、能打开的链接，按课程 / 按类型摊开，挑中的
 * 变成「学习」页里自己的一条任务（见 [Library] 的说明）。
 *
 * ## 2026-09-19 的口径变化（这一页现在长这样的原因）
 *
 * 用户先指着「学习」页里那半说「学习库和这里的功能重复了，把这里的删了吧」，
 * 又说「我希望用户可以在学习库中查看每个课程的详细内容」。于是：
 *   · 「学习」页只剩任务，`ui/learn` 那半的代码**整份删掉**（连同那一整页的界面与逻辑一起），
 *     这一页成为资源的**唯一**入口；
 *   · 「按课程」不再是"行内筛一下 + 顶部一行返回"：点一门课 = **进这门课的详情页**
 *     （课程信息 + 上课时间地点 + 资料按类型分组）；
 *   · 课表里**所有**课都列出来（全班还没攒到资料的课也列，进去至少能看到课程信息）。
 *
 * ## 几个刻意的设计取舍
 *
 * - **不吃缓存**：目录是别人的收藏汇总，服务端随时可能把某条撤下来。给用户看一份
 *   几小时前的缓存、让他"挑"一条已经不在目录里的链接，等于凭空造出死链。
 *   宁可每次进来现拉（慢网络下多转一圈），也不给"看着能用其实是旧的"。
 * - **整行点按 = 勾选/取消**：这里的点按不是写操作（写库只发生在底部那颗按钮上），
 *   而且选中状态立刻看得见 —— 与"整行绑定打卡"那种会误触致命的反面模式不同。
 * - **选中的东西一条都不丢**：切换"按课程/按类型"、点进点出课程，勾选都在（状态拎在
 *   handle 里，不随列表重建而清空）。
 * - **列表上的条数 = 点进去数得出来的条数**：两个数都从同一份 items 算（见
 *   [Library.courseRows] 的注释），不许一个走 `cat.courses`、一个走 `cat.items`。
 */
@Composable
fun LibraryScreen(
    db: CampusDb,
    // 取目录的动作可注入 —— 测试要能在不联网的情况下把"挑 → 加"整条路走完。
    // 传 null 用真接口（唯一调用点是底栏那一格）。
    load: (suspend () -> ApiResult<Catalog>)? = null,
) {
    val loader = load ?: rememberCatalogLoader()
    val h = rememberLibrary(db, loader)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            // 底部动作条是固定的：列表最后一行必须能让开它，否则最后一条永远点不到
            contentPadding = PaddingValues(top = 6.dp, bottom = if (h.picked.isEmpty()) 28.dp else 96.dp),
        ) {
            item { header(h) }
            item { modeRow(h) }
            // 搜索 + 刷新只在"列表"上有意义：进了课程详情就撤掉（详情自带返回）
            if (h.detail == null) item { searchRow(h) }

            val cat = h.cat
            when {
                cat == null && h.loading -> item { noteRow("正在取学习库…") }
                cat == null -> item { failRow(h) }
                // 详情**不看当前是哪一支**：「按类型」里点条目的课程标签也要能进那门课的详情，
                // 否则标签就是个死按钮（详情只画在"按课程"那一支的话点了没反应）。
                // 返回时回到进来前的视图，搜索词与 mode 都没动过。
                h.detail != null -> item { courseDetail(h, cat) }
                h.mode == MODE_COURSE -> item { courseList(h) }
                else -> item { itemList(h, cat) }
            }
            if (h.status != null) item { Spacer(Modifier.height(4.dp)); noteRow(h.status!!, C.cyan) }
        }

        if (h.picked.isNotEmpty()) {
            addBar(h, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 两条分类线：按课程 / 按类型。 */
private const val MODE_COURSE = 0
private const val MODE_KIND = 1

private val WD = listOf("", "一", "二", "三", "四", "五", "六", "日")

internal fun libCourseTag(name: String) = "lib-course-$name"
internal fun libItemTag(url: String) = "lib-item-$url"
internal fun libKindTag(key: String) = "lib-kind-$key"
internal fun libDetailTag(name: String) = "lib-detail-$name"

/** 「已加入」标记 / 「移出」按钮 —— 按 url 定位，别用"第几个同名文字"（列表顺序不是契约） */
internal fun libAddedTag(url: String) = "lib-added-$url"
internal fun libRemoveTag(url: String) = "lib-remove-$url"

/** 「加进/移出我的清单」的方框：整行现在是"打开资料"，点方框才是收藏 */
internal fun libCheckTag(url: String) = "lib-check-$url"
internal const val LIB_MODE_COURSE = "lib-mode-course"
internal const val LIB_MODE_KIND = "lib-mode-kind"
internal const val LIB_BACK = "lib-back"
internal const val LIB_ADD = "lib-add"
/** 详情页「加入我的课程」（库里的课 → 我的课表） */
internal const val LIB_PICK = "lib-pick"
internal const val LIB_RETRY = "lib-retry"
internal const val LIB_SEARCH = "lib-search"
internal const val LIB_REFRESH = "lib-refresh"

/** 「按类型」视图里那条资料的课程标签 —— 点它就进那门课的详情 */
internal fun libCourseChipTag(name: String) = "lib-course-chip-$name"

/** 目录页的状态。抽成对象是为了让"切分类/进出课程不清空勾选"成为可读的契约。 */
internal class LibraryHandle(
    val cat: Catalog?,
    val err: String?,
    val loading: Boolean,
    val mode: Int,
    val openCourse: String?,
    val filterKind: String?,
    val picked: Set<String>,
    /** **已经加进清单**的链接（与 [picked] 不同：那是"刚勾上还没提交"的暂存） */
    val added: Set<String>,
    val status: String?,
    val total: Int,
    /** 「按课程」那一列的全部行（有资料 / 暂无资料 / 自学桶） */
    val courseList: Library.CourseList,
    /** 当前打开的课程行；null = 还在课程列表上 */
    val detail: Library.CourseRow?,
    /** 当前打开那门课的上课时间段（别的班的课没有本机时间段，就是空的） */
    val slots: List<Slot>,
    val onMode: (Int) -> Unit,
    val onOpen: (String?) -> Unit,
    val onKind: (String?) -> Unit,
    val onToggle: (String) -> Unit,
    val onAdd: () -> Unit,
    /**
     * 挑课：把学习库里的一门课加进**我自己**的课表（服务端按令牌里的 uid 落库，
     * 所以挑别人在学的课也安全）。
     */
    val onPick: (String) -> Unit,
    /** 移出清单（选错了能去掉）。**必须由用户显式点那个「移出」** —— 见 [itemRow] 的注释 */
    val onRemove: (String) -> Unit,
    val onRetry: () -> Unit,
    /** 搜索词（课程名 / 资料名 / 来源 / 链接都算命中面）；空 = 不过滤 */
    val search: String,
    val onSearch: (String) -> Unit,
    /** 重拉目录：网络刚恢复、或同学刚探到新链接时，用户不该为了看一眼去重启 App */
    val onRefresh: () -> Unit,
)

@Composable
private fun rememberCatalogLoader(): suspend () -> ApiResult<Catalog> {
    val ctx = LocalContext.current
    val api = remember { CampusApi() }
    return remember(ctx) {
        {
            val tk = TokenStore.token(ctx)
            if (tk.isNullOrBlank()) ApiResult.Err(401, "先登录才能看学习库")
            else api.catalog(tk)
        }
    }
}

@Composable
private fun rememberLibrary(db: CampusDb, load: suspend () -> ApiResult<Catalog>): LibraryHandle {
    val ctx = LocalContext.current
    val api = remember { CampusApi() }
    var cat by remember { mutableStateOf<Catalog?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(MODE_COURSE) }
    var openCourse by remember { mutableStateOf<String?>(null) }
    var filterKind by remember { mutableStateOf<String?>(null) }
    // 搜索词跟着页面活：切分类、返回列表都不该把用户刚敲的字清掉
    var search by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 已经加进清单的那些（库里的挑选记录）—— 与"刚勾上还没提交"的 picked 是两回事。
    // 每次打开页面、每次增删之后都要重读：不重读的话，用户加完还看到"未加入"，
    // 于是又勾一遍、又点一次"加入"，最后被去重吃掉、状态栏说"都已在清单里"。
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var slots by remember { mutableStateOf<List<Slot>>(emptyList()) }
    val scope = rememberCoroutineScope()

    /** 重读"已在清单里"的集合。库操作一律下 IO —— 主线程碰 Room 会抛 */
    suspend fun reloadAdded() {
        added = withContext(Dispatchers.IO) {
            runCatching { Library.pickedUrls(Library.picks(db)) }.getOrDefault(emptySet())
        }
    }

    LaunchedEffect(tick) {
        loading = true
        val local = withContext(Dispatchers.IO) {
            runCatching { db.dao().courses().first() }.getOrDefault(emptyList())
        }
        courses = local
        reloadAdded()
        when (val r = load()) {
            is ApiResult.Ok -> { cat = r.value; err = null }
            is ApiResult.Err -> if (cat == null) err = r.message
        }
        loading = false
    }

    val items = cat?.items ?: emptyList()
    val courseList = if (cat == null) Library.CourseList(emptyList(), emptyList(), null)
    else Library.courseRows(cat!!, courses)
    val all = courseList.body + courseList.empty + listOfNotNull(courseList.self)
    val detail = openCourse?.let { n -> all.firstOrNull { it.name == n } }

    // 上课时间段只在**打开某门课**时才读（几行 SQL；为整个课程列表预读没必要）。
    // 课表还没同步过来时它就是空的 —— 详情页照实说，不编。
    val detailCourseId = detail?.course?.id
    LaunchedEffect(detailCourseId) {
        slots = if (detailCourseId == null) emptyList()
        else withContext(Dispatchers.IO) {
            runCatching { db.dao().slotsOfCourse(detailCourseId).first() }.getOrDefault(emptyList())
        }
    }

    return LibraryHandle(
        cat = cat,
        err = err,
        loading = loading,
        mode = mode,
        openCourse = openCourse,
        filterKind = filterKind,
        picked = picked,
        added = added,
        status = status,
        total = cat?.total ?: items.size,
        courseList = courseList,
        detail = detail,
        slots = slots,
        onMode = { m -> mode = m; openCourse = null; filterKind = null; status = null },
        onOpen = { c -> openCourse = c; status = null },
        onKind = { k -> filterKind = k; status = null },
        onToggle = { url -> picked = if (url in picked) picked - url else picked + url },
        onAdd = {
            val chosen = items.filter { it.url in picked }
            scope.launch {
                val picks = chosen.map { Library.pickOf(it, Library.matchCourse(courses, it.course)) }
                // 真正的新增条数由库那边算（重复链接会被丢掉）—— 报文里不能用
                // "已加入 N 条"糊过去：明明一条没加却说加成功了，用户只会以为界面在骗他
                val n = runCatching { withContext(Dispatchers.IO) { Library.add(db, picks) } }.getOrDefault(-1)
                status = when {
                    n < 0 -> "加入失败，再点一次试试"
                    n == 0 -> "这些都已在你的清单里了"
                    else -> "已加 ${n} 条到「学习」页的「学习库」"
                }
                if (n > 0) picked = emptySet()
                // ⚠️ 刷新界面状态要放在"成功/失败判断"之外单独做：这里刷新失败不该被
                // 上面那个 runCatching 吞成"加入失败"（数据其实已经落库，用户看到的是谎报）
                reloadAdded()
            }
        },
        onPick = { name ->
            scope.launch {
                val tk = TokenStore.token(ctx)
                if (tk.isNullOrBlank()) {
                    status = "先登录才能加入课程"
                } else {
                    status = when (val r = api.libraryPick(tk, name)) {
                        is ApiResult.Ok -> {
                            // 服务端已经把课写进我这一行了；再把计划拉下来，这门课才会出现在
                            // 我的课表/计划里（本地内容是服务端那份计划的镜像，不在这里自己造行）
                            when (val s = RemoteSync.sync(ctx, db, api, tk)) {
                                is ApiResult.Ok ->
                                    if (r.value.created) "已加入我的课程：$name"
                                    else "「$name」早就在你的课表里了"
                                is ApiResult.Err -> "已加入，但计划没刷下来。" + StudentError.TEXT
                            }
                        }
                        is ApiResult.Err -> r.message
                    }
                    tick++      // 重读本地课程 + 重拉目录：列表上的「在学」跟着变
                }
            }
        },
        onRemove = { url ->
            scope.launch {
                val n = runCatching { withContext(Dispatchers.IO) { Library.unpick(db, setOf(url)) } }
                    .getOrDefault(-1)
                status = when {
                    n < 0 -> "移出失败，再点一次试试"
                    n == 0 -> "这条已经不在清单里了"
                    else -> "已从「学习库」移出"
                }
                reloadAdded()
            }
        },
        onRetry = { tick++ },
        search = search,
        onSearch = { search = it },
        // 刷新 = 重跑那次 LaunchedEffect（重读课程 + 已在清单 + 重拉目录）。
        // 顺手清掉上一句状态语，否则"已加 2 条"会一直挂在刚刷新完的列表下面。
        onRefresh = { status = null; tick++ },
    )
}

// ------------------------------------------------------------------ 渲染

@Composable
private fun header(h: LibraryHandle) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("学习库", color = C.txt, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${h.total} 条", color = C.txt3, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            // 一句话说清它是什么、以及"挑了会发生什么" —— 不说清用户根本不敢点
            "同学探过、能打开的链接汇总在这儿。点一门课进去看它的课程信息与资料；" +
                "挑中的会变成你「学习」页里的一条任务。",
            color = C.txt3, fontSize = 11.5.sp, lineHeight = 17.sp,
        )
    }
}

@Composable
private fun modeRow(h: LibraryHandle) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)) {
        chip("按课程", h.mode == MODE_COURSE, LIB_MODE_COURSE) { h.onMode(MODE_COURSE) }
        Spacer(Modifier.width(8.dp))
        chip("按类型", h.mode == MODE_KIND, LIB_MODE_KIND) { h.onMode(MODE_KIND) }
    }
}

/**
 * 搜索 + 刷新。
 *
 * 搜索是**本地过滤**（目录拿到手之后在本地筛），不往服务器再要一次：数据量就这么大
 * （11 门课 / 91 条），本地筛是零延迟的；每敲一个字发一次请求，慢网络下反而难用。
 *
 * 搜索词跨两种视图通用：课程名命中就剩课程，资料名命中就剩资料。
 * 刷新按钮一按就重拉目录 —— 网络刚恢复、或同学刚探到新链接时，
 * 用户不该为了看一眼去重启 App（这份目录是刻意不吃缓存的，见文件头注释）。
 */
@Composable
private fun searchRow(h: LibraryHandle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = h.search,
            onValueChange = h.onSearch,
            singleLine = true,
            textStyle = TextStyle(color = C.txt, fontSize = 12.5.sp),
            cursorBrush = SolidColor(C.cyan),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (h.search.isEmpty()) {
                        Text("搜课程 / 资料名 / 来源", color = C.txt3, fontSize = 12.5.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .testTag(LIB_SEARCH)
                .background(C.card, RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .testTag(LIB_REFRESH)
                // 正在拉的时候不许再点：并发两次请求只会把界面状态搅乱
                .clickable(enabled = !h.loading) { h.onRefresh() }
                .background(C.card, RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (h.loading) "刷新中…" else "刷新",
                color = if (h.loading) C.txt3 else C.cyan,
                fontSize = 12.5.sp,
            )
        }
    }
}

@Composable
private fun chip(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .background(if (on) C.cardHi else C.card, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (on) C.txt else C.txt2, fontSize = 12.5.sp)
    }
}

@Composable
private fun noteRow(text: String, color: androidx.compose.ui.graphics.Color = C.txt3) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun failRow(h: LibraryHandle) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // 报错照实说：拉不到就说拉不到，别拿"暂时没有资料"糊过去（那就变成谎报）
        Text(h.err ?: "学习库拉取失败", color = C.txt2, fontSize = 12.5.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .testTag(LIB_RETRY)
                .background(C.card, RoundedCornerShape(9.dp))
                .clickable { h.onRetry() }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) { Text("重试", color = C.cyan, fontSize = 12.5.sp) }
    }
}

/**
 * 课程列表：**我课表里的每一门课都在**（有资料的在上面、按条数排；还没资料的在下面，
 * 标"暂无资料"），自学桶永远最后。
 *
 * 为什么没资料的课也列：用户要的是"每个课程的详细内容"都能看到。把它们藏起来，
 * 他会以为漏了自己的课 —— 而进去至少能看到教师/学分/上课时间地点这几样真信息。
 */
@Composable
private fun courseList(h: LibraryHandle) {
    val l = Library.searchCourseList(h.courseList, h.search)
    Column(modifier = Modifier.fillMaxWidth()) {
        if (l.body.isEmpty() && l.empty.isEmpty() && l.self == null) {
            // 「搜不到」和「库里就没有」是两件事，两句话说清：不然用户会以为资料被删了
            noteRow(if (h.search.isBlank()) "这里还没有资料" else "没搜到「${h.search.trim()}」相关的课程")
            return@Column
        }
        l.body.forEach { row -> courseRow(h, row) }
        if (l.empty.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("下面这些课暂时还没有资料", color = C.txt3, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            l.empty.forEach { row -> courseRow(h, row) }
        }
        l.self?.let { row -> courseRow(h, row) }
    }
}

@Composable
private fun courseRow(h: LibraryHandle, row: Library.CourseRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(libCourseTag(row.name))
            .clickable { h.onOpen(row.name) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, color = C.txt, fontSize = 14.sp, lineHeight = 19.sp)
            row.course?.teacher?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = C.txt3, fontSize = 11.sp)
            }
        }
        Text(
            if (row.count > 0) "${row.count} 条" else "暂无资料",
            color = C.txt3, fontSize = 12.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text("›", color = C.txt3, fontSize = 15.sp)
    }
    line()
}

/**
 * 课程详情页：这门课本身（教师 / 学分 / 类别 / 周次）+ 上课时间地点 + 它的资料（按类型分组）。
 *
 * 资料**按类型分组**而不是一长条流水：同学探来的链接里视频/文档/慕课/练习混在一起，
 * 平铺下来找"有没有题可以刷"得从头看到尾。
 */
@Composable
private fun courseDetail(h: LibraryHandle, cat: Catalog) {
    val row = h.detail ?: return
    val c = row.course
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LIB_BACK)
                .clickable { h.onOpen(null) }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text("‹ 学习库", color = C.cyan, fontSize = 13.sp, lineHeight = 18.sp) }

        Text(
            row.name,
            color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(libDetailTag(row.name)),
        )

        // ---------------------------------------------------------- 课程信息
        if (c != null) {
            val meta = buildList {
                c.teacher?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (c.credits > 0) add("${fmt(c.credits)} 学分")
                c.category?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta.joinToString(" · "), color = C.txt2, fontSize = 12.5.sp, style = Digits)
            }
            Spacer(Modifier.height(4.dp))
            Text(weeksText(c), color = C.txt3, fontSize = 11.sp, style = Digits)

            // ------------------------------------------------------ 上课时间地点
            Section("上课时间")
            if (h.slots.isEmpty()) {
                Text("课表里还没有这门课的时间段（同步课表后会有）。", color = C.txt3, fontSize = 11.5.sp)
            } else {
                h.slots.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("周${WD.getOrElse(s.weekday) { "?" }}", color = C.txt2, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(slotTimeLabel(s), color = C.txt, fontSize = 12.sp, style = Digits)
                        Spacer(Modifier.width(10.dp))
                        Text(s.room.orEmpty(), color = C.txt3, fontSize = 11.5.sp)
                    }
                    // 精确周次（`3-5、8、10、11 周`）—— 单双周/散周只看"第X~Y周"看不出来，
                    // 而它就是同学"这周到底去哪个教室"的答案，不能省
                    Text(
                        weeksLabel(s), color = C.txt3, fontSize = 10.5.sp, style = Digits,
                        modifier = Modifier.padding(start = 34.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
        } else if (row.name == Library.SELF_KEY) {
            Spacer(Modifier.height(6.dp))
            Text("与具体课程无关的通用内容", color = C.txt3, fontSize = 11.5.sp)
        } else {
            // 这门课来自学习库（同学在学 / 别的班的课），还没进我的课表。
            // 有什么显示什么：学习库给的公开信息（教师 / 学分 / 几个人在学）+ 加入按钮。
            // **上课时间不给** —— 那是"我的课表"才有的事实，别人的课表长什么样我不知道，
            // 编一个时间出来比留白更糟。
            val s = row.shared
            val meta = buildList {
                s?.teacher?.takeIf { it.isNotBlank() }?.let { add(it) }
                val cr = s?.credits ?: 0.0
                if (cr > 0) add("${fmt(cr)} 学分")
                val ln = s?.learners ?: 0
                if (ln > 0) add("$ln 人在学")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                meta.joinToString(" · ").ifEmpty { "这门课还没有更多信息" },
                color = C.txt2, fontSize = 12.5.sp, style = Digits,
            )
            Spacer(Modifier.height(4.dp))
            Text("还没加进你的课表 —— 加入后就能照它排任务、记资料。",
                color = C.txt3, fontSize = 11.5.sp)
            Spacer(Modifier.height(10.dp))
            pickButton(row.name, h)
        }

        // ---------------------------------------------------------- 资料（按类型分组）
        Section("资料")
        val mine = Library.itemsOf(cat, row)
        if (mine.isEmpty()) {
            // 照实说"这门课还没有"。**不写"暂时"**：同学探到链接时它会自己出现，
            // 说"暂时没有"会让用户以为过一会儿就好，回来刷新三次还是空
            Text("这门课还没有资料。", color = C.txt3, fontSize = 12.5.sp, lineHeight = 18.sp)
        } else {
            // 用户 2026-09-20 的坑：以前条目只能"勾选加清单"，没人知道它本身能点开
            Text("点任意一条就能打开看（不用先加进清单）；勾方框才加进我的清单。",
                color = C.txt3, fontSize = 11.5.sp, lineHeight = 16.sp)
            Library.kindGroups(mine).forEach { g ->
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(2.dp).height(11.dp).background(C.cyan, RoundedCornerShape(1.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${g.label} ${g.items.size}",
                        color = C.txt2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                g.items.forEach { it0 -> itemRow(h, it0, withKind = false) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 详情页的小节标题（与课表那边的课程详情面板同一套视觉）。 */
@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(12.dp).background(C.cyan, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, color = C.txt2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
}

/** 「按类型」那一列：全库的条目按类型筛，课程名写在副标题里。 */
@Composable
private fun itemList(h: LibraryHandle, cat: Catalog) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            chip("全部", h.filterKind == null, libKindTag("all")) { h.onKind(null) }
            cat.kinds.forEach { k ->
                Spacer(Modifier.width(8.dp))
                chip("${k.label} ${k.count}", h.filterKind == k.key, libKindTag(k.key)) { h.onKind(k.key) }
            }
        }
        val rows = Library.searchItems(
            if (h.filterKind != null) cat.items.filter { it.kind == h.filterKind } else cat.items,
            h.search,
        )
        if (rows.isEmpty()) {
            noteRow(if (h.search.isBlank()) "这一类下还没有资料" else "没搜到「${h.search.trim()}」相关的资料")
            return@Column
        }
        rows.forEach { it0 -> itemRow(h, it0) }
    }
}

@Composable
private fun itemRow(h: LibraryHandle, it0: CatalogItem, withKind: Boolean = true) {
    val ctx = LocalContext.current
    val inList = it0.url in h.added          // 已经在我的清单里了
    val on = it0.url in h.picked || inList   // 方框：勾上的 / 已加入的都打勾
    // 「看」和「收藏」拆成两件事（用户 2026-09-20：*学习库里还是不能预览课程…非要加进清单*）：
    //   点整行 = 打开资料本身（[Links.open]：Custom Tabs 在 App 内滑出，退出来还在库里；
    //            B 站那种页面塞进内嵌 WebView 是残废的，交给浏览器才放得动）
    //   点方框 = 加进/移出我的清单
    // 以前整行绑的是"勾选加清单"，于是**想看一眼内容只能先加进清单** —— 路径就是错的。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(libItemTag(it0.url))
            .clickable { Links.open(ctx, it0.url) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 选中标记用"☑/☐ + 颜色"两层提示：只看颜色的话，色弱用户分不出选没选中。
        // 点击区比 17dp 的方框大一圈：方框太小手指点不准，而整行现在管"打开"、不能替它挨这一下。
        Box(
            modifier = Modifier
                .testTag(libCheckTag(it0.url))
                .padding(top = 1.dp)
                .size(30.dp)
                .clickable(enabled = !inList) { h.onToggle(it0.url) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .background(if (on) C.cyan else C.card, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) { if (on) Text("✓", color = C.bg, fontSize = 11.sp) }
        }
        Spacer(Modifier.width(2.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(it0.title.ifBlank { it0.url }, color = C.txt, fontSize = 13.5.sp, lineHeight = 19.sp)
            if (inList) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .testTag(libAddedTag(it0.url))
                        .background(C.card, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Text("已加入", color = C.cyan, fontSize = 10.5.sp) }
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle(it0, h, withKind), color = C.txt3, fontSize = 11.5.sp, lineHeight = 16.sp)
            // 「按类型」是按类型摊开的，同一门课的资料会散落在各处 —— 每条挂一个**能点进那门课**
            // 的课程标签：看到一条好链接，顺手就能把整门课翻一遍（这正是用户要的"每门课的详细内容"）。
            if (h.mode == MODE_KIND) {
                val cn = it0.course.ifBlank { Library.SELF_KEY }
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .testTag(libCourseChipTag(cn))
                        .background(C.card, RoundedCornerShape(4.dp))
                        .clickable { h.onOpen(cn) }
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Text(cn, color = C.cyan, fontSize = 10.5.sp) }
            }
        }
        if (inList) {
            // 选错了能去掉：就地移出，不给确认框（撤销类一点即回，技能里用户的规矩）
            Text(
                "移出",
                modifier = Modifier
                    .testTag(libRemoveTag(it0.url))
                    .padding(start = 12.dp, top = 2.dp)
                    .clickable { h.onRemove(it0.url) },
                color = C.txt3,
                fontSize = 12.sp,
            )
        }
        // 「能点开看」得看得出来：行尾一个淡淡的 ▸（整行都是打开资料）
        Text(
            "▸",
            modifier = Modifier.padding(start = 6.dp, top = 1.dp),
            color = C.txt3,
            fontSize = 12.sp,
        )
    }
    line()
}

/**
 * 副标题只放"这条是什么"：类型 · 来源（· 课程）。**不放别人的 why** —— 那是个人语境。
 *
 * 课程详情页里已经按类型分了组，行里就不用再重复一遍类型（[withKind] = false）。
 */
private fun subtitle(it0: CatalogItem, h: LibraryHandle, withKind: Boolean = true): String {
    val parts = ArrayList<String>(3)
    if (withKind) {
        val kind = it0.kindLabel.ifBlank { Library.kindLabelOf(it0.kind) }
        if (kind.isNotBlank()) parts += kind
    }
    if (it0.source.isNotBlank()) parts += "来源 ${it0.source}"
    // 域名也露出来（用户 2026-09-20 要求）：共享库里的链接是**别人收录的**，
    // 点之前就该看得见要去哪。来源已经是域名时不重复写一遍。
    val host = Links.hostOf(it0.url)
    if (host.isNotBlank() && !it0.source.contains(host, ignoreCase = true)) parts += host
    // 课程不再塞进副标题：「按类型」那一列每条都挂了课程标签（可点进那门课），
    // 副标题里再来一遍就是同一行说两遍同样的话
    return parts.joinToString(" · ")
}

@Composable
private fun line() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
private fun addBar(h: LibraryHandle, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(C.bgSoft)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("已选 ${h.picked.size} 条", color = C.txt2, fontSize = 12.5.sp)
        Box(
            modifier = Modifier
                .testTag(LIB_ADD)
                .background(C.cyan, RoundedCornerShape(10.dp))
                .clickable { h.onAdd() }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) { Text("加到我的清单", color = C.bg, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

/**
 * 详情页的「加入我的课程」。
 *
 * 只在**这门课不在我课表里**时出现（我自己的课显示的是上课时间，不需要这个按钮）。
 * 点了之后服务端把它写进我的课程行，再把计划拉下来 —— 本地不自己造行，
 * 否则下一次同步会因为它不在服务端那份计划里而被清掉。
 */
@Composable
private fun pickButton(name: String, h: LibraryHandle) {
    Box(
        modifier = Modifier
            .testTag(LIB_PICK)
            .background(C.cyan, RoundedCornerShape(10.dp))
            .clickable { h.onPick(name) }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text("加入我的课程", color = C.bg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium) }
}
