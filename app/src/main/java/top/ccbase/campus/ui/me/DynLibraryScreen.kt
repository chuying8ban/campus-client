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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.DYN_PRESETS
import top.ccbase.campus.ui.theme.DynThumb
import androidx.compose.ui.graphics.asImageBitmap
import top.ccbase.campus.ui.theme.dynPresetsMatching
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetDyn
import top.ccbase.campus.widget.WidgetThemes

/**
 * 动态主题库（2026-10-18 用户："动态主题更丰富一些，做一个动态主题搜索库，里面要放大量的动态图片"；
 * "小部件也要支持动态主题，也是要有动态库"）。
 *
 * 这里没有一张 GIF，也没有一条网络请求 —— "大量的动态图片"是**现画的**：12 种动效 × 6 个色系
 * = 72 款，每款的缩略图就是它在你真机上的实时样子（同一个 [DynamicBackground] 缩到卡片大小）。
 * 好处：包体不涨、离线可用、点哪款立刻看到最终效果，不会出现"下载预览和真机不一样"。
 *
 * @param target "app" = 换 App 背景；"widget" = 换桌面小组件背景
 */
@Composable
fun DynLibraryScreen(target: String, onClose: () -> Unit) {
    // 系统返回键 = 回上一级（用户 2026-10-18："在图库等子页面按返回键要回退到上一级，而不是回桌面"）
    androidx.activity.compose.BackHandler { onClose() }
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<String?>(null) }
    val list = remember(q) { dynPresetsMatching(q) }

    Column(Modifier.fillMaxSize().overlaySurface.statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("动态主题库", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (target == "widget") "用于桌面小组件" else "用于 App 背景",
                color = C.txt3, fontSize = 10.5.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "关闭", color = C.txt3, fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        // 搜索：本地库，边打边筛，不用等网络
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
                if (q.isEmpty()) {
                    Text("搜：雪 / 雨 / 星 / 粉 / 赛博 …", color = C.txt3, fontSize = 12.5.sp)
                }
                BasicTextField(
                    value = q,
                    onValueChange = { q = it },
                    singleLine = true,
                    textStyle = TextStyle(color = C.txt, fontSize = 13.sp),
                    cursorBrush = SolidColor(C.violet),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (q.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "清空", color = C.violet, fontSize = 12.5.sp,
                    modifier = Modifier
                        .clickable { q = "" }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        Text(
            "共 ${DYN_PRESETS.size} 款 · 当前 ${list.size} 款" + (applied?.let { " · 已换成「$it」" } ?: ""),
            color = if (applied != null) C.violet else C.txt3,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )

        if (list.isEmpty()) {
            Text(
                "没有匹配的动效。试试：雪、雨、星、萤火、气泡、紫、粉",
                color = C.txt3, fontSize = 12.sp,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(list, key = { it.id }) { p ->
                    Column(Modifier.clickable {
                        if (target == "widget") {
                            WidgetThemes.applyNow(ctx, WidgetDyn.spec(p.kind, p.tint.argb))
                        } else {
                            AppearanceStore.setBackground(ctx, p.spec)
                        }
                        applied = p.name
                    }) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, C.line, RoundedCornerShape(12.dp)),
                        ) {
                            // 静态缩略图：同一套画笔只画一帧就缓存。原来这里每格都是一个活的
                            // DynamicBackground（一屏 6~8 个 60fps 动画同时跑），进页面就卡、
                            // 用户说的"每次点动态主题都要等半天"就是这么来的。
                            val thumb = remember(p.kind, p.tint.argb) {
                                DynThumb.bitmap(p.kind, p.tint.argb, 360)
                            }
                            androidx.compose.foundation.Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = p.name,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            p.name,
                            color = if (applied == p.name) C.violet else C.txt2,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }
}
