package top.ccbase.campus.ui.me

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.overlaySurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.MaterialLibrary
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetFrames
import top.ccbase.campus.widget.WidgetThemes
import android.graphics.BitmapFactory

/**
 * 本地素材库（2026-10-20 用户：「做两个专门的本地素材库（app主题和桌面小控件要分开），
 * 用户也可以在本地素材库里上传自己的文件」）。
 *
 * 一页库、两本账：进来时由 [slot] 决定读写哪一本，界面文案也跟着换。
 * - 「App 主题」这本只影响整屏背景；
 * - 「桌面小组件」这本只影响桌面那张卡。
 * 两边 spec 不同（`mat:app:xxx` / `mat:widget:xxx`），所以永远不会串台。
 */
@Composable
fun LocalMaterialsScreen(
    slotKey: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val slot = MaterialLibrary.Slot.entries.firstOrNull { it.key == slotKey } ?: MaterialLibrary.Slot.APP
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(MaterialLibrary.list(ctx, slot)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf<MaterialLibrary.Item?>(null) }
    val sel = remember(items) { MaterialLibrary.selected(ctx, slot) }

    fun refresh() {
        items = MaterialLibrary.list(ctx, slot)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        busy = "正在导入…"
        scope.launch {
            var ok = 0
            var bad = 0
            withContext(Dispatchers.IO) {
                uris.forEach { u ->
                    val it = MaterialLibrary.import(ctx, slot, u)
                    if (it != null) ok++ else bad++
                }
            }
            busy = null
            refresh()
            Toast.makeText(
                ctx,
                when {
                    ok > 0 && bad == 0 -> "已导入 $ok 个"
                    ok > 0 -> "导入 $ok 个，$bad 个格式不支持或文件损坏"
                    else -> "这几个文件都读不了（支持 jpg/png/gif/mp4/webm）"
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    BackHandler { onClose() }

    // 用 overlaySurface 而不是 .background(C.bg)：有背景图时它会自动让出底色，
    // 直接铺不透明底色会把用户设的背景盖住（AppearanceGuardTest 守着这条）。
    Column(Modifier.fillMaxSize().overlaySurface.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (slot == MaterialLibrary.Slot.APP) "App 主题素材库" else "桌面小组件素材库",
                        color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${items.size} 个素材 · 占用 ${human(MaterialLibrary.totalBytes(ctx, slot))}" +
                            if (slot == MaterialLibrary.Slot.WIDGET) " · 视频会抽帧成动画" else "",
                        color = C.txt3, fontSize = 11.sp,
                    )
                }
                Text("关闭", color = C.txt2, fontSize = 13.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }

            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.violet.copy(alpha = 0.14f))
                        .clickable(enabled = busy == null) {
                            pick.launch(arrayOf("image/*", "video/*"))
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        busy ?: "＋ 从手机里添加",
                        color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (slot == MaterialLibrary.Slot.WIDGET)
                            "还没有素材。\n\n两处来源：\n· 上方「从手机里添加」选本地图片/动图/视频\n· 去「横版图库」里点任意一张，先把「存到本地素材库」打开\n\n收进这里之后，桌面卡片随时切，不用重新下载。"
                        else
                            "还没有素材。\n\n两样都能收进来：手机里的图片/动图/视频，或者在线图库里看到的那张。\n收进来之后整屏背景随时切，删掉也不会影响别的。",
                        color = C.txt3, fontSize = 13.sp, lineHeight = 21.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(items, key = { it.id }) { item ->
                        Cell(
                            ctx = ctx,
                            slot = slot,
                            item = item,
                            selected = sel?.id == item.id,
                            busy = busy == item.id,
                            onClick = {
                                if (busy != null) return@Cell
                                busy = item.id
                                scope.launch {
                                    val okMsg = withContext(Dispatchers.IO) { apply(ctx, slot, item) }
                                    busy = null
                                    if (okMsg == null) {
                                        refresh()
                                        Toast.makeText(
                                            ctx,
                                            if (slot == MaterialLibrary.Slot.APP) "已换成这个背景" else "桌面卡片已换",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        Toast.makeText(ctx, okMsg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onDelete = { confirmDel = item },
                        )
                    }
                }
            }
        }

    confirmDel?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("从素材库删除？", color = C.txt, fontSize = 16.sp) },
            text = {
                Text(
                    "「${item.name}」会从本地删掉。如果它正在用，" +
                        (if (slot == MaterialLibrary.Slot.APP) "背景会退回原来的主题。" else "桌面会退回上一个主题。"),
                    color = C.txt2, fontSize = 13.sp,
                )
            },
            confirmButton = {
                Text(
                    "删除", color = Color(0xFFFF6B6B), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        val wasSel = sel?.id == item.id
                        MaterialLibrary.delete(ctx, slot, item.id)
                        if (wasSel) {
                            MaterialLibrary.select(ctx, slot, null)
                            if (slot == MaterialLibrary.Slot.WIDGET) {
                                WidgetThemes.set(ctx, WidgetThemes.DEFAULT)
                                TodayWidgetProvider.refresh(ctx)
                            } else {
                                AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
                            }
                        }
                        confirmDel = null
                        refresh()
                    }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    "留下", color = C.txt2, fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmDel = null }.padding(8.dp),
                )
            },
            containerColor = C.card,
        )
    }
}

/** 应用一件素材；返回 null = 成功，否则是给用户看的失败原因。 */
private fun apply(ctx: android.content.Context, slot: MaterialLibrary.Slot, item: MaterialLibrary.Item): String? {
    val spec = MaterialLibrary.spec(slot, item.id)
    return when (slot) {
        MaterialLibrary.Slot.APP -> {
            if (item.kind == MaterialLibrary.Kind.IMAGE) {
                val bmp = MaterialLibrary.file(ctx, slot, item.id)?.let {
                    BitmapFactory.decodeFile(it.absolutePath)
                } ?: return "这张图读不出来，建议删了重加"
                val stamp = top.ccbase.campus.ui.theme.BackgroundPhoto.saveBitmap(ctx, bmp)
                    ?: return "这张图存不进背景，换一张试试"
                top.ccbase.campus.ui.theme.BackgroundPhoto.clear(ctx)
                MaterialLibrary.select(ctx, slot, item.id)
                AppearanceStore.setBackground(ctx, "${AppearanceStore.BG_PHOTO}:$stamp")
                null
            } else {
                // 先验能不能播：截断的 mp4 有大小、放不出来，用它当背景 = 整屏黑
                val media = MaterialLibrary.resolve(ctx, spec) ?: return "这个素材读不出来"
                if (!top.ccbase.campus.ui.theme.UserMedia.playable(media.file)) return "这个文件放不出来（可能没下完整）"
                MaterialLibrary.select(ctx, slot, item.id)
                AppearanceStore.setBackground(ctx, spec)
                null
            }
        }
        MaterialLibrary.Slot.WIDGET -> {
            val prev = WidgetThemes.spec(ctx)
            // 桌面要的是"能抽成帧的画面"：抽不出来就等于设了也不显示，所以先试一遍
            val frames = runCatching { WidgetFrames.forSpec(ctx, spec, 900, 400) }.getOrNull()
            if (frames == null) {
                WidgetThemes.set(ctx, prev)
                return if (item.kind == MaterialLibrary.Kind.IMAGE) "这张图桌面用不了，换一张试试" else "这段视频抽不出画面"
            }
            MaterialLibrary.select(ctx, slot, item.id)
            WidgetThemes.set(ctx, spec)
            TodayWidgetProvider.refresh(ctx)
            null
        }
    }
}

@Composable
private fun Cell(
    ctx: android.content.Context,
    slot: MaterialLibrary.Slot,
    item: MaterialLibrary.Item,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb = remember(item.id) {
        MaterialLibrary.thumb(ctx, slot, item.id)
            ?: runCatching { MaterialLibrary.ensureThumb(ctx, slot, item) }.getOrNull()
    }
    val bmp = remember(thumb?.absolutePath, thumb?.lastModified()) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    Box(
        Modifier
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(12.dp))
            .background(C.card)
            .border(
                if (selected) 1.5.dp else 0.5.dp,
                if (selected) C.violet else C.line,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() },
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (busy) "…" else item.kind.label, color = C.txt3, fontSize = 11.sp)
            }
        }
        // 底部一条压暗：名字要能看清，不能糊在图里
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                item.name, color = Color.White, fontSize = 9.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.kind != MaterialLibrary.Kind.IMAGE) {
            Box(
                Modifier
                    .align(Alignment.TopStart).padding(5.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(item.kind.label, color = Color.White, fontSize = 8.sp)
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd).padding(5.dp)
                    .clip(RoundedCornerShape(5.dp)).background(C.violet)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text("在用", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 删除放右下角，跟"点图就用"分开，避免误触
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(topStart = 8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onDelete() }
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("✕", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
        }
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("处理中…", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

private fun human(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
