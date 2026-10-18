package top.ccbase.campus.ui.me

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.widget.ArtItem
import top.ccbase.campus.widget.ArtRepo
import top.ccbase.campus.widget.TodayWidgetProvider
import top.ccbase.campus.widget.WidgetThemes

/**
 * 在线图库：给桌面小组件挑一张插画当底图。
 *
 * 为什么单独一屏而不是塞进外观页：挑图要一屏能看十几张，塞在设置页里必然又变成一长条。
 * 这里顶上搜索、下面两列网格，选一张就下载存本机并立刻刷到桌面上。
 *
 * 图是真从服务器拉的（`/updates/art/`），**只在选中那一张时下载**，不会一次拉满。
 */
@Composable
fun ArtGalleryScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<GalleryState>(GalleryState.Loading) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        state = runCatching { withContext(Dispatchers.IO) { ArtRepo.fetchCatalog() } }
            .fold(
                onSuccess = { if (it.isEmpty()) GalleryState.Empty else GalleryState.Ready(it) },
                onFailure = { GalleryState.Failed(it.message ?: "网络不给力") },
            )
    }

    Column(Modifier.fillMaxSize().overlaySurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("插画图库", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "关闭",
                color = C.txt3,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        // 搜索框：输入即筛（清单在内存里），不用等网络
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(C.card, RoundedCornerShape(10.dp))
                .border(1.dp, C.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) {
                Text("搜：星空 / 樱花 / 赛博 / 少女 …", color = C.txt3, fontSize = 12.5.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = C.txt, fontSize = 13.sp),
                cursorBrush = SolidColor(C.violet),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        msg?.let {
            Text(
                it,
                color = C.amber,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when (val s = state) {
            GalleryState.Loading -> CenterHint("正在读图库…", spinner = true)
            is GalleryState.Failed -> CenterHint("图库暂时读不出来：${s.why}")
            GalleryState.Empty -> CenterHint("图库里还没有图")
            is GalleryState.Ready -> {
                val shown = s.items.filter { it.matches(query) }
                if (shown.isEmpty()) {
                    CenterHint("没有匹配「$query」的图")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            ArtCard(
                                item = item,
                                busy = busyId == item.id,
                                onClick = {
                                    if (busyId != null) return@ArtCard
                                    busyId = item.id
                                    msg = null
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            val bmp = ArtRepo.full(item)
                                            val saved = bmp != null && WidgetThemes.saveUserImage(ctx, bmp)
                                            if (saved) {
                                                WidgetThemes.set(ctx, WidgetThemes.USER_IMAGE_SPEC)
                                                TodayWidgetProvider.refresh(ctx)
                                            }
                                            saved
                                        }
                                        busyId = null
                                        if (ok) onClose() else msg = "这张图没下下来，换一张试试"
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

private sealed interface GalleryState {
    data object Loading : GalleryState
    data object Empty : GalleryState
    data class Failed(val why: String) : GalleryState
    data class Ready(val items: List<ArtItem>) : GalleryState
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

    Column(Modifier.clickable { onClick() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1B1824))
                .border(1.dp, C.line, RoundedCornerShape(12.dp)),
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
            if (busy) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x88000000)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = C.violet, strokeWidth = 2.dp, modifier = Modifier.height(20.dp)) }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(item.title, color = C.txt2, fontSize = 11.5.sp, maxLines = 1)
    }
}
