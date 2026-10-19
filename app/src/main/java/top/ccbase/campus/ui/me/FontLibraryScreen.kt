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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.AppFont
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.FontOpt
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.WidgetFont

/**
 * 字体库（2026-10-19 用户："字体专门搞一个字体库（要分开），里面多放一些字体，尤其是要有一些艺术字体"）。
 *
 * 为什么是独立一页而不是一排胶囊：
 *  - 20 多款挤在设置页里，卡片文字全用同一款字体渲染，选哪个全凭猜；
 *  - 这一页**每一款都用自己的字体渲染样张**（"C语言程序设计 / 3-5节 11:25"），
 *    点之前就能看出"毛笔楷"和"龙藏狂草"差在哪。
 *
 * 「要分开」= 入口分两个：从「App 字体」进来只改 App（`app_font`），
 * 从「小组件字体」进来只改桌面那张卡（`widget_font`）。同一条街两个门，招牌不同。
 */
@Composable
fun FontLibraryScreen(
    /** "app" / "widget" —— 只改对应的那一份 pref */
    target: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val widget = target == "widget"
    val store = if (widget) WidgetFont else AppFont
    var sel by remember(target) { mutableStateOf(store.read(ctx)) }
    var appOnlyHint by remember(target) { mutableStateOf(false) }
    val art = FontOpt.entries.filter { it.art }
    val sys = FontOpt.entries.filter { !it.art }

    Column(Modifier.fillMaxSize().overlaySurface.statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("字体库", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (widget) "只改桌面那张卡上的字" else "只改 App 里的字",
                    color = C.txt3, fontSize = 11.sp,
                )
            }
            Text(
                "关闭",
                color = C.txt3, fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        if (appOnlyHint) {
            Text(
                "这几款是随 App 打包的字体文件，桌面进程读不到，所以只能在 App 里生效。",
                color = C.txt2, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            fun section(title: String, list: List<FontOpt>) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "$title · ${list.size} 款",
                        color = C.txt3, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.id }) { opt ->
                    // 2026-10-20 真机验证：系统族（22 款）在桌面上真的生效；
                    // 但桌面包里没有我们的 @font 资源 —— MIUI 桌面是用自己的 Resources 充气 RemoteViews 的，
                    // 我们包里的 ttf 它找不到，会静默回落成默认字体（用户看到"选了没反应"）。
                    // 所以小组件库里先把艺术字体标成"仅 App"，不让它假装能选。
                    val appOnly = widget && opt.art
                    FontCard(opt, on = opt.id == sel.id, appOnly = appOnly) {
                        if (!appOnly) {
                            store.set(ctx, opt)
                            sel = opt
                        }
                    }
                }
            }
            if (widget) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "艺术字体 · ${art.size} 款（桌面不支持自带字体，这几款只在 App 里生效）",
                        color = C.txt3, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(art, key = { it.id }) { opt ->
                    FontCard(opt, on = opt.id == sel.id, appOnly = true) {
                        appOnlyHint = true
                    }
                }
            } else {
                section("艺术字体", art)
            }
            section("系统字体", sys)
        }
    }
}

@Composable
private fun FontCard(opt: FontOpt, on: Boolean, appOnly: Boolean = false, onClick: () -> Unit) {
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
