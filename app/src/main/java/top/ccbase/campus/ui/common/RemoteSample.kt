package top.ccbase.campus.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import top.ccbase.campus.ui.theme.C

/**
 * 云端样张（字体预览 PNG）的最小加载器。
 *
 * 为什么不引 Coil：这里的全部需求就是「按 URL 拉一张 20KB 的 PNG、解码、缓存」。
 * 为一个字体库塞一整套图片框架，安装包和构建时间都白涨。
 *
 * 缓存用 [Collections.synchronizedMap] + 容量上限：字体库一屏最多十几张，
 * 但用户会来回切板块，没有缓存每次都要重新拉。
 */
@Composable
fun RemoteSample(
    url: String,
    modifier: Modifier = Modifier,
    height: Int = 46,
) {
    var bmp by remember(url) { mutableStateOf(cache[url]) }
    LaunchedEffect(url) {
        if (bmp != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ByteArrayOutputStream()
                top.ccbase.campus.net.Net.dial.stream(url) { buf, n, _ -> bytes.write(buf, 0, n) }
                BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            cache[url] = loaded
            bmp = loaded
        }
    }
    val img = bmp
    if (img != null) {
        Image(
            bitmap = img,
            contentDescription = null,
            modifier = modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        // 占位保持高度不变：样张来得晚不该让整块列表跳一下
        Box(
            modifier.fillMaxWidth().height(height.dp)
                .clip(RoundedCornerShape(6.dp))
                // C.line 自带 alpha，copy(alpha) 会把它覆盖掉（ThemeTokenTest 守着这条）
                .background(C.line),
        )
    }
}

private val cache: MutableMap<String, ImageBitmap> =
    Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 40
    })
