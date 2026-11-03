// FeaturedThemes.kt — 「精选主题」这一块（App 主题页最上面那一排）
//
// 一张卡片 = 一套搭配。点一下就整套换掉（明暗 + 强调色 + 背景 + 浓淡），
// 不用自己从色环上试。和图库卡片一样：**点即生效**，不满意再点旁边那张就行 ——
// 这里不弹二次确认，多一次点击只会在反复挑的时候烦人。
package top.ccbase.campus.ui.me

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.ThemePack
import top.ccbase.campus.ui.theme.ThemePacks

@Composable
fun FeaturedThemesSection(
    // 这两个参数只是为了让测试能喂假数据：线上调用一律用缺省实现
    // （清单从 dl.ccbase.top 取，套用走 ThemePacks.apply）。
    load: suspend (android.content.Context) -> List<ThemePack> = { ThemePacks.fetch(it) },
    applyPack: (android.content.Context, ThemePack) -> ThemePacks.Result = { c, p -> ThemePacks.apply(c, p) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var packs by remember { mutableStateOf<List<ThemePack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf(ThemePacks.appliedId(ctx)) }
    var covers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }

    // 第一次取清单会慢：App 要先试一条链路、不通再换一条（Net 的既有机制，
    // 换过之后本机记住，下次就快）。实测模拟器上第一次要几十秒 —— 光转圈什么也不说，
    // 用户会以为卡死。所以 5 秒后换一句实话，不假装快。
    var hintSlow by remember { mutableStateOf(false) }
    LaunchedEffect(loading, packs.isEmpty()) {
        if (!loading || packs.isNotEmpty()) { hintSlow = false; return@LaunchedEffect }
        delay(5000)
        hintSlow = true
    }

    suspend fun load() {
        loading = true
        err = null
        val got = runCatching { withContext(Dispatchers.IO) { load(ctx) } }
        loading = false
        if (got.isSuccess) {
            packs = got.getOrNull().orEmpty()
            if (packs.isEmpty()) err = "服务器上还没有主题"
        } else {
            err = "取不到主题清单（检查网络再试）"
        }
    }

    LaunchedEffect(Unit) {
        load()
        // 封面一张张补：清单先出来，别等 8 张图下完才显示这一排
        packs.forEach { p ->
            val bmp = runCatching { withContext(Dispatchers.IO) { ThemePacks.cover(p) } }.getOrNull()
            if (bmp != null) covers = covers + (p.id to bmp)
        }
    }

    Text(
        "竖版预览：点一下整套换，明暗、主色、背景一起变",
        fontSize = 10.5.sp,
        color = C.txt3,
        lineHeight = 15.sp,
    )
    Spacer(Modifier.height(8.dp))

    when {
        loading && packs.isEmpty() -> Text(
            if (hintSlow) "网络有点慢，还在取…（第一次会慢一点）" else "正在取主题…",
            fontSize = 10.5.sp,
            color = C.txt3,
        )
        err != null && packs.isEmpty() -> Row2(err!!) { scope.launch { load() } }
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(packs, key = { it.id }) { p ->
                // 「正在用」不能只看"我套过它"：用户套完之后又手动拖了色环／
                // 切了明暗，那就不再是这一套了 —— 所以还要比对当前状态。
                val stillThis = applied == p.id &&
                    AppearanceStore.accent.value == ThemePacks.sanitizeAccent(p.accent) &&
                    AppearanceStore.theme.value == p.mode
                PackCard(
                    pack = p,
                    cover = covers[p.id],
                    active = stillThis,
                    busy = busy == p.id,
                ) {
                    if (busy != null) return@PackCard
                    busy = p.id
                    note = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { applyPack(ctx, p) }
                        busy = null
                        if (r.ok) {
                            applied = p.id
                            note = "已经换成「${p.name}」"
                        } else {
                            note = r.error ?: "这套没套上"
                        }
                    }
                }
            }
        }
    }

    note?.let {
        Spacer(Modifier.height(7.dp))
        Text(it, fontSize = 10.5.sp, color = if (it.startsWith("已经")) C.violet else C.amber,
            lineHeight = 15.sp)
    }
}

@Composable
private fun Row2(msg: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(msg, fontSize = 10.5.sp, color = C.txt3, modifier = Modifier.weight(1f))
        Text(
            "重试",
            fontSize = 11.sp,
            color = C.violet,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(C.violet.copy(alpha = 0.16f))
                .clickable { onRetry() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PackCard(
    pack: ThemePack,
    cover: Bitmap?,
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    // 竖版卡：9:16，和手机屏幕一个比例 —— 封面就是"这套主题套在 App 上"的样子。
    // 2026-11-03 用户：旧的 132x99 横方块"咋都是小组件的主题"，一排看过去像桌面卡片，
    // 认不出这是 App 的主题。改成竖版后高度跟着宽度走（112 * 16/9 ≈ 199dp）。
    val w = 112.dp
    val h = 199.dp
    Column(
        Modifier
            .width(w)
            .clip(RoundedCornerShape(14.dp))
            .background(C.card)
            .border(
                if (active) 1.5.dp else 1.dp,
                if (active) C.violet.copy(alpha = 0.75f) else C.line,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(C.violet.copy(alpha = 0.30f), C.card),
                    ),
                ),
        ) {
            cover?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = pack.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 下半截压暗：名字压在图上，白字要压得住（封面本身也是暗底，这条不能省）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.42f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.42f),
                            1.00f to Color.Black.copy(alpha = 0.80f),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 9.dp, end = 9.dp, bottom = 8.dp),
            ) {
                Text(
                    pack.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pack.tag.isNotBlank()) {
                    Text(
                        pack.tag,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.74f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = C.violet,
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (active && !busy) {
                Text(
                    "正在用",
                    fontSize = 9.5.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(C.violet.copy(alpha = 0.88f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
