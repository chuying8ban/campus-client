package top.ccbase.campus.ui.me

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.BackgroundPhoto
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.widget.ArtItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import top.ccbase.campus.widget.ArtRepo
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.ui.theme.UserMedia
import top.ccbase.campus.widget.WidgetThemes

/**
 * 图库结果的**内存缓存**（2026-10-19：用户要"快速出现、边浏览边搜"）。
 *
 * 只活在这个进程里，按 (搜索词, 方向, 是否动图) 分桶，最多 12 桶 —— 一桶就是一次
 * 已经付过网络代价的查询结果，切标签、退出去再点回来都不该再等一轮。
 */
private object ArtMemo {
    private val map = object : LinkedHashMap<String, List<ArtItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<ArtItem>>?) = size > 12
    }

    fun get(k: String): List<ArtItem>? = synchronized(map) { map[k] }

    fun put(k: String, v: List<ArtItem>) {
        if (v.isEmpty()) return
        synchronized(map) { map[k] = v }
    }
}

private fun memoKey(q: String, orient: String, anim: Boolean) = "$q|$orient|$anim"

/**
 * 在线图库：给 App 页面背景（竖图）或桌面小组件（横图）挑一张插画。
 *
 * 两条数据路：
 *  · **没输入**：先看服务器上预先挑好的那批（首屏快，不依赖搜索接口）；
 *  · **输入关键词**：打自己服务器的 `/api/art/search`，查的是 safebooru 整个库（几百万张），
 *    往下滑自动翻页 —— 所以"图库有多大"不再受 App 里那 976 张限制。
 *
 * 一屏 6 张（3 列 × 2 行）：手机上既能看清缩略图，又不用一直滚。
 */
@Composable
fun ArtGalleryScreen(kind: String, target: String, onClose: () -> Unit) {
    // 系统返回键 = 回上一级（用户 2026-10-18："在图库等子页面按返回键要回退到上一级，而不是回桌面"）
    androidx.activity.compose.BackHandler { onClose() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val portrait = kind == "portrait"
    val orientKey = if (portrait) "portrait" else "landscape"

    var query by remember { mutableStateOf("") }
    var curated by remember { mutableStateOf<List<ArtItem>?>(null) }
    var live by remember { mutableStateOf<List<ArtItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 按「搜索」按钮时跳过 400ms 防抖（打字才需要防抖，按钮是明确意图）
    var tick by remember { mutableIntStateOf(0) }
    // 搜索过程的一句话（"正在搜…" / "没搜到，再点一次"），别让用户对着空白干等
    var stage by remember { mutableStateOf<String?>(null) }
    // 「动图」板块（用户 2026-10-18："图库里专门放一个动图板块专门放动图，app 主题和小控件主题都要有"）：
    // 打开后只问服务端要会动的那批，不再和静态混排。
    var animOnly by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    // 翻到第二行以后就算"在往下看"：收起搜索栏，给图片腾地方
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 2 } }

    // 打开就**先把已经搜过的那批贴出来**，同时后台刷新（2026-10-19 用户：
    // "每次点动态主题都要等半天，希望能快速出现、浏览的同时进行搜索"）。
    // 缓存按 (词, 方向, 是否动图) 分桶，切标签/切板块回来是瞬时的。
    LaunchedEffect(orientKey, animOnly) {
        ArtMemo.get(memoKey("", orientKey, animOnly))?.let {
            if (it.isNotEmpty() && live.isEmpty()) {
                live = it
                loading = false
                stage = "先显示上次的结果（${it.size} 张），正在后台刷新…"
            }
        }
        // 预搜索：静态板块进来时顺手把"全部动图"也拉好，点动图不用再等一轮
        if (!animOnly && ArtMemo.get(memoKey("", orientKey, true)) == null) {
            runCatching {
                withContext(Dispatchers.IO) { ArtRepo.searchLive("", 1, orientKey, animOnly = true) }
            }.onSuccess { pg ->
                ArtMemo.put(memoKey("", orientKey, true), pg.items.filter { it.ofOrient(orientKey) }.ifEmpty { pg.items })
            }
        }
    }

    // 首屏：服务器上预先挑好的那批（10 分钟本机缓存，断网退回旧缓存）
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { ArtRepo.fetchCatalog(ctx) } }
            .onSuccess { all ->
                curated = if (portrait) all.filter { it.ofOrient("portrait") }
                else all.filter { it.ofOrient("landscape") && it.ofKind("illustration") }
            }
            .onFailure { error = "图库清单读不出来" }
        loading = false
    }

    // 搜索：停手 400ms 再发，别每敲一个字打一次网络。
    //
    // 2026-10-18 用户反馈：搜不到的时候界面上只有一句"搜索失败，检查下网络"，也不知道在干嘛。
    // 实际原因通常不是他的网，而是**上游第一次查要现算**（冷缓存 7 秒左右，几乎贴着客户端超时）。
    // 所以现在：① 自动重试一次；② 每一步都写进 [stage] 给用户看；③ 失败时明说"再点一次搜索"。
    LaunchedEffect(query, tick, animOnly) {
        val q = query.trim()
        // 动图板块空着进来 = 看全部动图（服务端按 score 给精选），**不要**拿种子词替他填搜索框
        // ——用户 2026-10-19："搜索点到动图为什么会自动切到少女标签"。标签应该是他点的，不是我们点的。
        if (q.isEmpty() && !animOnly) {
            live = emptyList(); page = 1; hasMore = false; error = null; hint = null; stage = null
            return@LaunchedEffect
        }
        if (q.isEmpty() && animOnly) stage = "正在读全部动图…"
        if (tick == 0) delay(400)
        loading = true; error = null
        var last: String? = null
        for (attempt in 1..2) {
            stage = when {
                attempt != 1 -> "第一次没返回，正在再试一次…"
                q.isEmpty() -> "正在读全部动图…"
                else -> "正在搜「$q」…（整库查询，第一次要几秒）"
            }
            val r = runCatching {
                withContext(Dispatchers.IO) { ArtRepo.searchLive(q, 1, orientKey, animOnly = animOnly) }
            }
            if (r.isSuccess) {
                val pg = r.getOrThrow()
                // 竖版只放竖图、横版只放横图（用户 2026-10-19："做好文件隔离"）。
                // 服务端已经按 orient 过滤，这里是第二道闸：上游偶发把方形图判成两边都算，
                // 在竖版里看到横图会让人以为点错了库。
                val kept = pg.items.filter { it.ofOrient(orientKey) }
                // 2026-10-19 用户："动图库搜不到动图" —— 接口确实返回了 57 条，方向字段却是空的/对不上，
                // 于是被这道闸全部滤掉，界面只剩"没有动图了"。宁可先显示出来，再把实情写清楚，
                // 也不要拿一个空白页让人以为图库空了。
                val relaxed = if (kept.isEmpty() && pg.items.isNotEmpty()) pg.items else kept
                ArtMemo.put(memoKey(q, orientKey, animOnly), relaxed)
                live = relaxed; page = 1; hasMore = pg.hasMore; hint = pg.hint
                if (kept.isEmpty() && pg.items.isNotEmpty()) {
                    stage = "接口返回 ${pg.items.size} 条，但方向标记对不上（${pg.items.map { it.orient }.distinct()}）—— 已先全部显示"
                    loading = false
                    return@LaunchedEffect
                }
                stage = if (kept.isEmpty()) {
                    if (animOnly) "没有动图了 —— 换个词试试（风景 / 少女 / 猫），或点「全部」看静态"
                    else "「$q」这次没搜到 —— 再点一下「搜索」多试一次"
                } else if (animOnly) {
                    if (q.isEmpty()) "精选动图 ${kept.size} 张（点标签可筛）" else "「$q」找到 ${kept.size} 张动图"
                } else {
                    "「$q」找到 ${kept.size} 张"
                }
                loading = false
                return@LaunchedEffect
            }
            last = r.exceptionOrNull()?.message ?: "未知错误"
            if (attempt == 1) delay(700)
        }
        // 两次都失败：不清掉已经显示出来的图，只在上面写清楚原因和下一步
        error = "搜索超时（$last）—— 服务器这次没赶上，再点一下「搜索」；多点几次通常就出来了"
        stage = null
        loading = false
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        if (query.isBlank() && !animOnly) return
        loadingMore = true
        scope.launch {
            val q = query.trim()
            runCatching {
                withContext(Dispatchers.IO) { ArtRepo.searchLive(q, page + 1, orientKey, animOnly = animOnly) }
            }
                .onSuccess { pg ->
                    // 同 id 只留一份：翻页衔接处偶有重复，重复的 key 会让网格直接抛异常
                    val have = live.mapTo(HashSet()) { i -> i.id }
                    live = live + pg.items.filter { it.id !in have && it.ofOrient(orientKey) }
                    page += 1
                    hasMore = pg.hasMore
                }
            loadingMore = false
        }
    }

    // 滑到倒数第 3 张就去要下一页
    val nearEnd by remember {
        derivedStateOf {
            val total = grid.layoutInfo.totalItemsCount
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) loadMore() }

    val shown = when {
        animOnly -> live
        query.isBlank() -> curated ?: emptyList()
        else -> live
    }

    Column(Modifier.fillMaxSize().overlaySurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (portrait) "竖版图库" else "横版图库",
                color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "关闭",
                color = C.txt3,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        // 往下翻就把搜索栏收起来（用户 2026-10-18："图库往下翻时要隐藏搜索栏"）。
        // 只收搜索框和说明文字，标题行的「关闭」和板块切换都留着 —— 那两样是随时要用的。
        AnimatedVisibility(
            visible = !scrolled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            // ⚠️ AnimatedVisibility 的内容不是 Column：它按 Box 语义把多个子项叠在同一位置。
            // 上一版"搜索行"和"▶ 动 = …"说明是两个并列子项 → 文字直接压成一团（用户 2026-10-19 截图）。
            Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                Modifier
                    .weight(1f)
                    .background(C.card, RoundedCornerShape(10.dp))
                    .border(1.dp, C.line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        if (portrait) "搜整库：少女 / 樱花 / 原神 …" else "搜整库：风景 / 城市 / 星空 …",
                        color = C.txt3, fontSize = 12.5.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; tick = 0 },
                    singleLine = true,
                    textStyle = TextStyle(color = C.txt, fontSize = 13.sp),
                    cursorBrush = SolidColor(C.violet),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { tick += 1 },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .height(44.dp)
                    .background(C.violet.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .border(1.dp, C.violet.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable { if (query.isNotBlank()) tick += 1 }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("搜索", color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            }

            // 角标说明：静态/动态混排，不解释一句，用户以为是"加载失败"或"点了没动"
            Text(
                "▶ 动 = 会动的画面，点了直接当动态壁纸（要下整段原片，比静态图慢几秒）",
                color = C.txt3, fontSize = 10.5.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            )
            }
        }

        // 板块切换：全部（静态+动态混排）/ 动图（只要会动的）。两个图库共用这一屏，
        // 所以 App 背景和桌面小组件都自动有这个板块。
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(false to "全部", true to "▶ 动图").forEach { (isAnim, label) ->
                val on = animOnly == isAnim
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) C.violet.copy(alpha = 0.20f) else C.card)
                        .border(1.dp, if (on) C.violet else C.line, RoundedCornerShape(50))
                        .clickable { animOnly = isAnim }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text(
                        label,
                        color = if (on) C.violet else C.txt2,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }

        // 热门词：新用户不知道能搜什么，空着进来只看到"精选 N 张"很容易以为没内容。
        // 直接给一排能点的词，点一下就等于搜一次。
        val hotWords = if (portrait) {
            listOf("原神", "少女", "樱花", "校服", "星空", "赛博", "和服", "猫")
        } else {
            listOf("原神", "风景", "城市", "夜景", "海", "赛博", "天空", "机甲")
        }
        AnimatedVisibility(
            visible = !scrolled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            hotWords.forEach { w ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (query.trim() == w) C.violet.copy(alpha = 0.22f) else C.card)
                        .border(
                            1.dp,
                            if (query.trim() == w) C.violet.copy(alpha = 0.6f) else C.line,
                            RoundedCornerShape(20.dp),
                        )
                        // 再点一次同一个标签 = 取消（用户 2026-10-19："我希望可以再次点击标签取消标签"）
                        .clickable {
                            if (query.trim() == w) { query = ""; tick += 1 } else { query = w; tick += 1 }
                        }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(w, color = if (query.trim() == w) C.violet else C.txt2, fontSize = 12.sp)
                }
            }
        }
        }

        if (query.isBlank() && !animOnly) {
            // 常态提示：搜索是打服务器现查，偶尔会慢/空一次，先告诉他该怎么办，别让他以为是坏了
            Text(
                "搜不到就再点一次「搜索」—— 查的是整库，偶尔要等上游几秒",
                color = C.txt3, fontSize = 10.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
        Text(
            when {
                stage != null -> stage!!
                loading -> "读取中…"
                animOnly && query.isBlank() -> "精选动图 ${shown.size} 张 · 点标签可筛 · 往下滑看更多"
                query.isBlank() -> "精选 ${shown.size} 张 · 搜关键词可查整库"
                else -> "「${query.trim()}」${shown.size} 张" + if (hasMore) " · 继续往下滑" else ""
            },
            color = C.txt3, fontSize = 10.5.sp,
            modifier = Modifier.padding(start = 16.dp, top = 7.dp, bottom = 2.dp),
        )

        msg?.let {
            Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        error?.let {
            Text(
                it, color = C.amber, fontSize = 11.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when {
            // 已经有图就别整块换成转圈：搜索时旧图留着，上面那行字在走路，才不像卡死
            loading && shown.isEmpty() -> CenterHint("正在读图库…", spinner = true)
            shown.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CenterHint(
                    when {
                        hint != null -> hint!!
                        query.isBlank() -> "这批里还没有图"
                        else -> "没搜到「${query.trim()}」，换个词试试"
                    },
                )
                // 空白页必须能自己再试一次：2026-10-19 用户截图"动图上什么都没有"，
                // 但同一时刻接口是有 57 条的 —— 十有八九是他那次请求没回来，而不是真没有。
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { tick += 1 }) {
                    Text(if (animOnly) "重新读一次动图" else "重新搜一次", color = C.violet, fontSize = 12.sp)
                }
            }
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.id }) { item ->
                    ArtCard(
                        item = item,
                        busy = busyId == item.id,
                        onClick = {
                            if (busyId != null) return@ArtCard
                            busyId = item.id
                            // 动态要点下载整段视频（1~10MB），不给一句话用户会以为卡死
                            msg = if (item.animated) {
                                "正在下载这段动图…选中的是高画质原片，请耐心等几秒"
                            } else {
                                null
                            }
                            var bad = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    // 动态：整段视频存下来当"动态壁纸"（解码成 Bitmap 就只剩一帧）
                                    if (item.animated) {
                                        val bytes = ArtRepo.bytes(item) ?: return@withContext false
                                        // 存到各自的槽：给桌面小组件换视频不能把 App 背景也换掉
                                        val slot = if (target == "background") {
                                            UserMedia.Slot.APP
                                        } else {
                                            UserMedia.Slot.WIDGET
                                        }
                                        val kind = UserMedia.saveBytes(ctx, bytes, slot)
                                            ?: return@withContext false
                                        // 落盘之后立刻验能不能播：截断的 mp4 有大小、放不出来，
                                        // 用它当背景 = 整屏黑（用户 2026-10-19 截图）
                                        val saved = UserMedia.current(ctx, slot)
                                        if (saved == null || !UserMedia.playable(saved.file)) {
                                            UserMedia.clear(ctx, slot)
                                            bad = true
                                            return@withContext false
                                        }
                                        if (target == "background") {
                                            BackgroundPhoto.clear(ctx)
                                            AppearanceStore.setBackground(ctx, UserMedia.spec(kind))
                                        } else {
                                            // 桌面那边是先抽 4 帧再轮播的；抽不出帧就等于"设了也不显示"。
                                            // 所以先试抽一遍，抽不出来就退回原来的主题并把原因说清楚，
                                            // 别让用户以为是自己点错了。
                                            val prev = WidgetThemes.spec(ctx)
                                            val spec = UserMedia.spec(kind)
                                            WidgetThemes.set(ctx, spec)
                                            val frames = runCatching {
                                                top.ccbase.campus.widget.WidgetFrames
                                                    .forSpec(ctx, spec, 900, 400)
                                            }.getOrNull()
                                            if (frames == null) {
                                                WidgetThemes.set(ctx, prev)
                                                TodayWidgetProvider.refresh(ctx)
                                                bad = true
                                                return@withContext false
                                            }
                                            TodayWidgetProvider.refresh(ctx)
                                        }
                                        return@withContext true
                                    }
                                    val bmp = ArtRepo.full(item) ?: return@withContext false
                                    if (target == "background") {
                                        // App 页面背景：和系统相册选图走同一条落盘逻辑（会量明暗极值算遮罩）
                                        val stamp = BackgroundPhoto.saveBitmap(ctx, bmp)
                                        if (stamp == null) false else {
                                            AppearanceStore.setBackground(
                                                ctx,
                                                "${AppearanceStore.BG_PHOTO}:$stamp",
                                            )
                                            true
                                        }
                                    } else {
                                        val saved = WidgetThemes.saveUserImage(ctx, bmp)
                                        if (saved) {
                                            WidgetThemes.set(ctx, WidgetThemes.USER_IMAGE_SPEC)
                                            TodayWidgetProvider.refresh(ctx)
                                        }
                                        saved
                                    }
                                }
                                busyId = null
                                if (ok) onClose() else msg =
                                    if (bad) "这段动图没下完整，再点一次试试" else "这张图没下下来，换一张试试"
                            }
                        },
                    )
                }
                if (loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = C.violet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, spinner: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))
        if (spinner) {
            CircularProgressIndicator(color = C.violet, strokeWidth = 2.dp, modifier = Modifier.height(22.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(text, color = C.txt3, fontSize = 12.5.sp)
    }
}

@Composable
private fun ArtCard(item: ArtItem, busy: Boolean, onClick: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, item.id) {
        value = withContext(Dispatchers.IO) { ArtRepo.thumb(item) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (item.orient == "portrait") 3f / 4f else 16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1B1824))
            .border(1.dp, C.line, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 「动」角标：静态/动态混在一条流里，不标出来用户点开才知道是视频
        if (item.animated) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xA6000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶ 动", color = Color.White, fontSize = 9.5.sp)
            }
        }
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x88000000)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = C.violet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) }
        }
    }
}
