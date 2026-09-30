package top.ccbase.campus.ui.me

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.BG_PRESET_PREFIX
import top.ccbase.campus.ui.theme.BackgroundLayer
import top.ccbase.campus.ui.theme.BackgroundPhoto
import top.ccbase.campus.ui.theme.BgPreset
import top.ccbase.campus.ui.theme.BgPresetSurface
import top.ccbase.campus.ui.theme.BgPresets
import top.ccbase.campus.ui.theme.BgStyle
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.palette

/**
 * 「外观」—— 主题三档 + 背景（内置 12 款 / 自己上传的照片）。
 *
 * 三条对用户的承诺，代码必须让它们为真（`AppearanceGuardTest` 会扫源码守住）：
 *  1. **不申请任何存储权限**：选图走系统照片选择器（`PickVisualMedia`），
 *     App 只拿到你挑中的那一张的读权限；这条路上"不申请"才是正确答案，申请了反而多余。
 *  2. **图片只存本机、不上传**：写进 `filesDir`，这一整块代码里没有任何网络调用。
 *  3. **浮层的底必须恒不透明**：外观页盖在下面那页之上，用 `overlaySurface`；
 *     用了 `pageSurface` 就变成"设过背景时浮层一个像素都不画"，下层那页的字会透上来。
 *
 * ## 为什么这页顶上要放一块**预览**
 *
 * 这页是整屏浮层、底是**不透明**的，所以用户在这里点色卡时，看到的只有那 92×52 的色卡本身
 * —— 背景到底铺满屏是什么样，必须退出去才知道。2026-09-30 用户那句"切换背景根本看不出区别"，
 * 一半是旧配色确实没有对比（见 `Background.kt` 的注释），另一半就是**这个**：改完看不见。
 * 于是这里放一截真机顶部的 1:1 画面（同一个 [BackgroundLayer] 画的），点哪张当场就变。
 */
@Composable
fun AppearanceScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeChoice = AppearanceStore.theme.value      // 读快照：改完当场反映
    val bgChoice = AppearanceStore.background.value
    val dark = C.bg.luminance() <= 0.5f
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val photoSet = bgChoice.startsWith(AppearanceStore.BG_PHOTO)

    // 系统照片选择器：**不申请存储权限**（这也是它存在的意义）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // 用户在系统相册里点了返回 = 什么都没选，不该报错、也不该改设置
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        msg = null
        scope.launch {
            val stamp = withContext(Dispatchers.IO) { BackgroundPhoto.save(ctx, uri) }
            busy = false
            if (stamp == null) {
                msg = "这张图读不出来，换一张试试"
            } else {
                AppearanceStore.setBackground(ctx, "${AppearanceStore.BG_PHOTO}:$stamp")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .overlaySurface
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("外观", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("关闭", color = C.txt3, fontSize = 13.sp, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "主题决定文字和面板的颜色，背景铺在最底下。点一下**立即生效** —— " +
                "下面的预览是真机屏幕顶部那一截（1:1），不用退出去看效果。设置只存在这台手机上。",
            color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))

        // ── 预览 ──────────────────────────────────────────────────────────
        SectionTitle2("预览")
        Spacer(Modifier.height(6.dp))
        PreviewTile(bgChoice)
        Spacer(Modifier.height(16.dp))

        // ── 主题 ──────────────────────────────────────────────────────────
        SectionTitle2("主题")
        listOf(
            AppearanceStore.DARK to "深色（默认）",
            AppearanceStore.LIGHT to "浅色",
            AppearanceStore.SYSTEM to "跟随系统",
        ).forEach { (value, label) ->
            ChoiceRow(label, selected = themeChoice == value) {
                AppearanceStore.setTheme(ctx, value)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 背景 ──────────────────────────────────────────────────────────
        SectionTitle2("背景")
        ChoiceRow(
            "默认（纯色）",
            selected = bgChoice == AppearanceStore.BG_SOLID,
        ) { AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID) }

        Spacer(Modifier.height(8.dp))
        Text(
            "内置 ${BgPresets.size} 款，两组风格（程序生成的渐变，不带图片文件）",
            color = C.txt3, fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(10.dp))

        // 两组分开摆：同一色相在两组里各有一款，组内才是"同一种味道"的横向比较
        BgStyle.entries.forEach { style ->
            Text(style.label, color = C.txt2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BgPresets.filter { it.style == style }.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { preset ->
                            BgSwatch(
                                preset = preset,
                                dark = dark,
                                selected = bgChoice == BG_PRESET_PREFIX + preset.id,
                            ) { AppearanceStore.setBackground(ctx, BG_PRESET_PREFIX + preset.id) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 自己的照片 ────────────────────────────────────────────────────
        SectionTitle2("自己上传一张")
        if (photoSet) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(92.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .overlaySurface
                        .border(2.dp, C.violet, RoundedCornerShape(10.dp)),
                ) {
                    PhotoSlice(bgChoice)
                }
                Spacer(Modifier.width(10.dp))
                Text("正在用你选的那张图", color = C.violet, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
        ChoiceRow(
            when {
                busy -> "正在处理这张图…"
                photoSet -> "换一张照片"
                else -> "从相册选一张"
            },
            selected = false,
            enabled = !busy,
        ) {
            // 走系统照片选择器：不申请存储权限，App 只拿到这一张的读权限
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        msg?.let {
            Text(it, color = C.amber, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.card)
                .border(1.dp, C.line, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("关于你的图片", color = C.txt, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            listOf(
                "选图走系统照片选择器，App 不申请任何存储权限，也看不到相册里其它的照片。",
                "图片会先压到长边 1440 再保存，存在 App 自己的目录里，随时能换回默认（换了就删掉）。",
                "这张图只在这台手机上，不上传服务器，也不进任何备份。",
                "铺上去的遮罩按**这张图自己的明暗**算：深色照片几乎不压（图看得清），亮图自动压回去（字看得清）。",
            ).forEach {
                Text("· $it", color = C.txt2, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Action2("恢复默认外观", "深色主题 + 纯色底，并删掉本机保存的那张图") {
            BackgroundPhoto.clear(ctx)
            AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
            AppearanceStore.setTheme(ctx, AppearanceStore.DARK)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 真机屏幕顶部那一截，**1:1**。
 *
 * 外框只有 [PREVIEW_H] 高，里面那层按真机比例（[PHONE_ASPECT]）铺满整屏该有的高度，
 * 再把上沿裁出来 —— 所以这里看到的渐变走向、光晕位置、卡片留白，和退出这页之后一模一样。
 * 要是直接把整张背景压缩进一个矮框里，色卡和真机就会长得不一样（预览就白做了）。
 */
@Composable
private fun PreviewTile(bg: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(PREVIEW_H)
            .clip(RoundedCornerShape(14.dp))
            .overlaySurface
            .border(1.dp, C.lineHi, RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(PHONE_ASPECT)) {
            BackgroundLayer(bg, palette)
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("第 8 周 · 周六", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("今天 2 节课", color = C.txt2, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(C.card)
                        .border(1.dp, C.line, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text("下一节 · 09:50", color = C.txt3, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("高等数学 A", color = C.txt, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("教三楼 305 · 10:40 下课", color = C.txt2, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 照片缩略图：同样是"屏幕顶部那一截"，所以它和预览、和退出去之后的真机长得一致 */
@Composable
private fun PhotoSlice(bg: String) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(PHONE_ASPECT)) { BackgroundLayer(bg, palette) }
    }
}

/**
 * 一块色卡。
 *
 * 色卡里画的是**这款背景的真实渲染**（[BgPresetSurface] 与背景层同一段绘制代码、
 * 同一份遮罩），不是"代表色"。老版本拿 `preset.brush()` 画一个不带遮罩的纯渐变，
 * 结果色卡看着挺浓、铺到屏幕上却是一片灰 —— 色卡得和真机长得一样才有意义。
 */
@Composable
private fun BgSwatch(preset: BgPreset, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(92.dp).clickable { onClick() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) C.violet else C.lineHi,
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            BgPresetSurface(preset, dark, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            preset.name + if (selected) " ✓" else "",
            color = if (selected) C.violet else C.txt2,
            fontSize = 11.5.sp,
        )
    }
}

private val PREVIEW_H = 190.dp

/** 390×844 是参考机型（1080×2340、density 2.75）的 dp 尺寸，预览按这个比例裁 */
private const val PHONE_ASPECT = 390f / 844f

/** 一行可选中的设置项（勾在右边，不用 Material 的 RadioButton —— 主题里已经有一套配色） */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) (if (selected) C.violet else C.txt) else C.txt3,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("✓", color = C.violet, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionTitle2(text: String) {
    Text(text, color = C.txt3, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Action2(text: String, hint: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp)) {
        Text(text, color = C.txt.copy(alpha = 0.88f), fontSize = 14.sp)
        hint?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, color = C.txt3, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
