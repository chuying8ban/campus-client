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
import top.ccbase.campus.widget.ArtRepo
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.ui.theme.UserMedia
import top.ccbase.campus.widget.WidgetThemes

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
    // 动图板块没有"精选"这批，空着进来就是空白 —— 给一个种子词，用户随手改。
    val animSeed = if (portrait) "少女" else "风景"
    LaunchedEffect(animOnly) {
        if (animOnly && query.isBlank()) query = animSeed
    }

    LaunchedEffect(query, tick, animOnly) {
        val q = query.trim()
        if (q.isEmpty()) {
            live = emptyList(); page = 1; hasMore = false; error = null; hint = null; stage = null
            return@LaunchedEffect
        }
        if (tick == 0) delay(400)
        loading = true; error = null
        var last: String? = null
        for (attempt in 1..2) {
            stage = if (attempt == 1) "正在搜「$q」…（整库查询，第一次要几秒）"
            else "第一次没返回，正在再试一次…"
            val r = runCatching {
                withContext(Dispatchers.IO) { ArtRepo.searchLive(q, 1, orientKey, animOnly = animOnly) }
            }
            if (r.isSuccess) {
                val pg = r.getOrThrow()
                live = pg.items; page = 1; hasMore = pg.hasMore; hint = pg.hint
                stage = if (pg.items.isEmpty()) {
                    if (animOnly) "「$q」这个关键词没有动图 —— 换个词试试（风景 / 少女 / 猫）"
                    else "「$q」这次没搜到 —— 再点一下「搜索」多试一次"
                } else if (animOnly) {
                    "「$q」找到 ${pg.items.size} 张动图"
                } else {
                    "「$q」找到 ${pg.items.size} 张"
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
        if (loading || loadingMore || !hasMore || query.isBlank()) return
        loadingMore = true
        scope.launch {
            val q = query.trim()
            runCatching {
                withContext(Dispatchers.IO) { ArtRepo.searchLive(q, page + 1, orientKey, animOnly = animOnly) }
            }
                .onSuccess { pg ->
                    // 同 id 只留一份：翻页衔接处偶有重复，重复的 key 会让网格直接抛异常
                    val have = live.mapTo(HashSet()) { i -> i.id }
                    live = live + pg.items.filter { it.id !in have }
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
                        .clickable { query = w }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(w, color = if (query.trim() == w) C.violet else C.txt2, fontSize = 12.sp)
                }
            }
        }

        if (query.isBlank()) {
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
            shown.isEmpty() -> CenterHint(
                when {
                    hint != null -> hint!!
                    query.isBlank() -> "这批里还没有图"
                    else -> "没搜到「${query.trim()}」，换个词试试"
                },
            )
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
                                        if (target == "background") {
                                            BackgroundPhoto.clear(ctx)
                                            AppearanceStore.setBackground(ctx, UserMedia.spec(kind))
                                        } else {
                                            WidgetThemes.set(ctx, UserMedia.spec(kind))
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
                                if (ok) onClose() else msg = "这张图没下下来，换一张试试"
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
