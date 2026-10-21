package top.ccbase.campus.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.data.local.RemoteFontStore
import top.ccbase.campus.data.remote.FontCatalog
import top.ccbase.campus.data.remote.FontDownloads
import top.ccbase.campus.domain.RemoteFont
import top.ccbase.campus.ui.common.RemoteSample
import top.ccbase.campus.ui.theme.AppFont
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.FontOpt
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.WidgetFont

/**
 * 字体库（2026-10-21 用户口径改版；前身是 2026-10-19 的「字体专门搞一个字体库」）。
 *
 * 这一版的两件硬要求，逐条对着用户原话：
 *  ① **「字体库里设两个板块，本地字体库和在线字体下载，下载的字体放到本地字体库里」**
 *     → 顶部两个板块切换；下载完的字体立刻出现在「本地字体库」里，不用重启、不用重进。
 *  ② **「装新版本时不连带着下字体……只要下载一次就一直能用，更新新版本也不能丢」**
 *     → 几十款中文字体只放云端，APK 不涨；ttf 落在 `filesDir/fonts/`（覆盖安装不清），
 *       已下载的判定**只看磁盘上文件在不在**，不信 prefs。
 *
 * 两个入口仍然各改各的（`app_font` / `widget_font`）：从「App 字体」进来只改 App。
 * 小组件读不到磁盘上的字体文件（RemoteViews 只认本包资源），所以小组件那侧
 * 只列系统族 + 随包字体，下载区明说"桌面用不了"，而不是摆一堆点了没反应的卡片。
 */
@Composable
fun FontLibraryScreen(
    /** "app" / "widget" —— 只改对应的那一份 pref */
    target: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val widget = target == "widget"
    var appKey by remember(target) { mutableStateOf(AppFont.state.value.key) }
    var widgetSel by remember(target) { mutableStateOf(WidgetFont.read(ctx)) }
    var tab by remember { mutableStateOf(0) }        // 0 = 本地字体库，1 = 在线字体下载
    var hint by remember { mutableStateOf<String?>(null) }
    var indexNote by remember { mutableStateOf<String?>(null) }

    val installed = RemoteFontStore.installed.value
    val catalog = FontCatalog.list.value
    val downloaded = catalog.filter { it.id in installed }

    // 进页面先扫盘（用户可能在系统设置里清过数据），再拉一次云端清单
    LaunchedEffect(Unit) {
        RemoteFontStore.ensureScanned(ctx)
        FontCatalog.loadCached(ctx)
        indexNote = if (FontCatalog.refresh(ctx)) "云端清单已更新"
        else "当前用随包清单（没网也能用已下载的字体）"
    }
    // 盯住 pref：删掉正在用的那一款时得自动切回系统字体，界面不能一直显示已选的幽灵
    LaunchedEffect(target) {
        while (true) {
            appKey = AppFont.state.value.key
            widgetSel = WidgetFont.read(ctx)
            kotlinx.coroutines.delay(400)
        }
    }

    val art = FontOpt.entries.filter { it.art }
    val sys = FontOpt.entries.filter { !it.art }

    Column(Modifier.fillMaxSize().overlaySurface.statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("字体库", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (widget) "只改桌面那张卡上的字" else "只改 App 里的字") +
                        " · 已下载 ${downloaded.size} 款",
                    color = C.txt3, fontSize = 11.sp,
                )
            }
            Text(
                "关闭",
                color = C.txt3, fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        // ── 两个板块（用户口径：本地字体库 / 在线字体下载）──────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            TabPill("本地字体库 ${art.size + sys.size + downloaded.size}", tab == 0,
                Modifier.weight(1f).testTag("fontTab:local")) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabPill("在线字体下载 ${catalog.size}", tab == 1,
                Modifier.weight(1f).testTag("fontTab:online")) { tab = 1 }
        }

        hint?.let {
            Text(
                it, color = C.txt2, fontSize = 11.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tab == 0) {
                // ── 板块一：本地字体库 ────────────────────────────────────
                if (downloaded.isNotEmpty()) {
                    header("已下载 · ${downloaded.size} 款（换新版也还在）")
                    items(downloaded, key = { "dl:" + it.id }) { rf ->
                        DownloadedCard(
                            rf = rf,
                            on = appKey == "file:" + rf.id,
                            onPick = {
                                when {
                                    widget -> hint = "下载的字体只能改 App 里的字 —— 桌面小组件读不到磁盘上的字体文件。"
                                    AppFont.setRemote(ctx, rf.id, rf.name) -> {
                                        appKey = "file:" + rf.id
                                        hint = "App 字体已换成「${rf.name}」"
                                    }
                                    else -> hint = "这款字体本机读不出来，删掉重新下一次试试。"
                                }
                            },
                            onDelete = {
                                val wasOn = AppFont.state.value.remoteId == rf.id
                                if (wasOn) AppFont.resetToSystem(ctx)
                                RemoteFontStore.delete(ctx, rf.id)
                                if (wasOn) appKey = AppFont.state.value.key
                                hint = if (wasOn) "已删除「${rf.name}」，字体切回了系统默认"
                                else "已删除「${rf.name}」"
                            },
                        )
                    }
                }
                if (widget) {
                    header("系统字体 · ${sys.size} 款")
                    items(sys, key = { it.id }) { opt ->
                        FontCard(opt, on = widgetSel.id == opt.id, tag = "font:" + opt.id) {
                            WidgetFont.set(ctx, opt)
                            widgetSel = opt
                            hint = "小组件字体已换成「${opt.label}」"
                        }
                    }
                    // 2026-10-20 真机验证：系统族（22 款）在桌面上真的生效；
                    // 但桌面包里没有我们的 @font 资源 —— MIUI 桌面是用自己的 Resources 充气 RemoteViews 的，
                    // 我们包里的 ttf 它找不到，会静默回落成默认字体（用户看到"选了没反应"）。
                    // 所以小组件库里先把艺术字体标成"仅 App"，不让它假装能选。
                    header("随包艺术字体 · ${art.size} 款（桌面读不到，只在 App 里生效）")
                    items(art, key = { it.id }) { opt ->
                        FontCard(opt, on = false, appOnly = true, tag = "font:" + opt.id) {
                            hint = "这几款随 App 打包，桌面进程读不到，只能在 App 里生效。"
                        }
                    }
                } else {
                    header("随包艺术字体 · ${art.size} 款（装了就有，不用下载）")
                    items(art, key = { it.id }) { opt ->
                        FontCard(opt, on = appKey == "sys:" + opt.id, tag = "font:" + opt.id) {
                            AppFont.set(ctx, opt)
                            appKey = "sys:" + opt.id
                            hint = "App 字体已换成「${opt.label}」"
                        }
                    }
                    header("系统字体 · ${sys.size} 款")
                    items(sys, key = { it.id }) { opt ->
                        FontCard(opt, on = appKey == "sys:" + opt.id, tag = "font:" + opt.id) {
                            AppFont.set(ctx, opt)
                            appKey = "sys:" + opt.id
                            hint = "App 字体已换成「${opt.label}」"
                        }
                    }
                }
            } else {
                // ── 板块二：在线字体下载 ──────────────────────────────────
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        (indexNote ?: "正在取字体清单…") +
                            " · 共 ${catalog.size} 款，下载后进「本地字体库」，升级 App 不会丢",
                        color = C.txt3, fontSize = 11.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                if (widget) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "桌面小组件只能用它自己包里的字体 —— 下载的字体文件桌面进程读不到，" +
                                "所以这里下的字体只改 App 里的字。要给桌面换字体，用上面「系统字体」那几款。",
                            color = C.txt2, fontSize = 11.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                } else {
                    FontCatalog.grouped().forEach { (group, fonts) ->
                        header("$group · ${fonts.size} 款")
                        items(fonts, key = { "on:" + it.id }) { rf ->
                            OnlineCard(
                                rf = rf,
                                installed = rf.id in installed,
                                progress = FontDownloads.progress[rf.id],
                                error = FontDownloads.error[rf.id],
                                onDownload = { FontDownloads.start(ctx, rf) { FontCatalog.loadCached(ctx) } },
                                onPick = {
                                    if (AppFont.setRemote(ctx, rf.id, rf.name)) {
                                        appKey = "file:" + rf.id
                                        hint = "App 字体已换成「${rf.name}」，也在「本地字体库」里了"
                                    } else {
                                        hint = "这款字体读不出来，删掉重新下一次试试。"
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分节标题（跨两列） */
private fun LazyGridScope.header(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            color = C.txt3, fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun TabPill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) C.violet.copy(alpha = 0.18f) else C.card)
            .border(1.dp, if (on) C.violet.copy(alpha = 0.55f) else C.line, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) C.violet else C.txt2, fontSize = 12.sp, maxLines = 1)
    }
}

/** 已下载的字体卡：样张就用**它自己的字体**渲染，选之前能看出差别 */
@Composable
private fun DownloadedCard(
    rf: RemoteFont,
    on: Boolean,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val fam: FontFamily? = remember(rf.id) { RemoteFontStore.family(ctx, rf.id) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) C.violet.copy(alpha = 0.14f) else C.card)
            .border(1.dp, if (on) C.violet.copy(alpha = 0.55f) else C.line, RoundedCornerShape(14.dp))
            .clickable { onPick() }
            .testTag("font:dl:" + rf.id)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(
            "C语言程序设计",
            color = if (on) C.violet else C.txt,
            fontSize = 17.sp,
            fontFamily = fam,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text("3-5 节 · 11:25-13:50", color = C.txt3, fontSize = 11.sp, fontFamily = fam, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rf.name, color = if (on) C.violet else C.txt2, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (on) {
                Text("已选", color = C.violet, fontSize = 10.sp)
            } else {
                Text(
                    "删除", color = C.txt3, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDelete() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 云端字体卡：样张是服务端渲染好的 PNG —— 没下载也能看出长什么样，选字体不该靠猜名字 */
@Composable
private fun OnlineCard(
    rf: RemoteFont,
    installed: Boolean,
    progress: Float?,
    error: String?,
    onDownload: () -> Unit,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.card)
            .border(1.dp, C.line, RoundedCornerShape(14.dp))
            .testTag("online:" + rf.id)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        RemoteSample(FontCatalog.BASE + (rf.preview ?: (rf.id + ".png")), height = 46)
        Spacer(Modifier.height(7.dp))
        Text(rf.name, color = C.txt, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(sizeText(rf.bytes), color = C.txt3, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        when {
            progress != null -> {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = C.violet,
                )
                Spacer(Modifier.height(4.dp))
                Text("下载中 ${(progress * 100).toInt()}%", color = C.txt3, fontSize = 10.sp)
            }
            installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已下载", color = C.violet, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "用这款", color = C.violet, fontSize = 10.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(C.violet.copy(alpha = 0.16f))
                        .clickable { onPick() }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            error != null -> Column {
                Text("下载失败：$error", color = C.red, fontSize = 10.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    "重试", color = C.violet, fontSize = 10.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(C.violet.copy(alpha = 0.16f))
                        .clickable { onDownload() }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            else -> Text(
                "下载", color = C.violet, fontSize = 10.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(C.violet.copy(alpha = 0.16f))
                    .clickable { onDownload() }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes >= 1048576) String.format("%.1f MB", bytes / 1048576.0)
    else String.format("%.0f KB", bytes / 1024.0)

@Composable
private fun FontCard(
    opt: FontOpt,
    on: Boolean,
    appOnly: Boolean = false,
    tag: String = "",
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) C.violet.copy(alpha = 0.14f) else C.card)
            .border(
                1.dp,
                if (on) C.violet.copy(alpha = 0.55f) else C.line,
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .then(if (appOnly) Modifier.alpha(0.45f) else Modifier)
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        // 样张：就两行真会出现的字，一眼看出适不适合课表
        Text(
            "C语言程序设计",
            color = if (on) C.violet else C.txt,
            fontSize = 17.sp,
            fontFamily = opt.compose,
            fontWeight = if (opt.weight > 0) FontWeight(opt.weight) else null,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "3-5 节 · 11:25-13:50",
            color = C.txt3,
            fontSize = 11.sp,
            fontFamily = opt.compose,
            fontWeight = if (opt.weight > 0) FontWeight(opt.weight) else null,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(opt.label, color = if (on) C.violet else C.txt2, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (on) Text("已选", color = C.violet, fontSize = 10.sp)
            else if (appOnly) Text("仅 App", color = C.txt3, fontSize = 10.sp)
        }
    }
}
