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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AccentSpec
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
import top.ccbase.campus.ui.theme.presetById

/**
 * 「外观」—— 两页，互不干扰：
 *
 *  ① **App 主题**：明暗、强调色（12 套预置 + 自己的色环）、页面背景；
 *  ② **桌面小组件**：只改桌面上那张卡的配色，和 App 里用什么色是两件事。
 *
 * 2026-10-18 重做（用户："外观那一块重做一下，要简洁明了，app 主题和小控件主题要分开"）：
 * 原来是一页长条，App 主题、背景、小组件配色全串在一起，中间还夹着 158dp 的色环，
 * 想改个小组件配色得滑过半个屏幕。现在拆成两页切换，每页只放这件事的东西；
 * 色环默认收起（正在用自定义色时才展开），页面长度立减一屏。
 *
 * 代码要守住的三条承诺（`AppearanceGuardTest` 扫源码盯着）：
 *  1. **不申请任何存储权限**：选图走系统照片选择器（`PickVisualMedia`），只拿到选中那一张；
 *  2. **图片只存本机、不上传**：写进 `filesDir`，这一整块没有任何网络调用；
 *  3. **浮层的底必须恒不透明**：用 `overlaySurface`，否则下层页面的字会透上来。
 *
 * ## 为什么这页顶上要放一块**预览**
 *
 * 这页是整屏浮层、底不透明，所以点色卡时用户看到的只有那 92×52 的色卡本身 ——
 * 背景铺满屏是什么样，必须退出去才知道（2026-09-30："切换背景根本看不出区别"）。
 * 于是这里放一截真机顶部的 1:1 画面（同一个 [BackgroundLayer] 画的），点哪张当场就变。
 */
@Composable
fun AppearanceScreen(
    onClose: () -> Unit,
    initialTab: Int = 0,
    // (kind, target)：kind = photo/illustration，target = background/widget
    onOpenGallery: (String, String) -> Unit = { _, _ -> },
    /** 作为底部导航的一格时传 true：不画标题和「关闭」（那两样只在整屏浮层里有意义） */
    embedded: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var showPresetBoard by rememberSaveable { mutableStateOf(false) }

    // 空白处的触摸由 `overlaySurface` 统一吃掉（见 Theme.kt），这里不用再挂
    Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.overlaySurface)) {
        if (!embedded) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("外观", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 命中区补到 44dp：13sp 的几个字手指一偏就点空（点空又不会穿透到下面了，
                // 但用户只会觉得"点了没反应"）
                Text(
                    "关闭",
                    color = C.txt3,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        } else {
            Text(
                if (tab == 0) "App 主题" else "桌面小组件",
                color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
        }

        // 两页切换：一眼看得出"这是两件事"，也不会改错对象
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaneTab("App 主题", tab == 0) { tab = 0 }
            PaneTab("桌面小组件", tab == 1) { tab = 1 }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 28.dp),
        ) {
            if (tab == 0) {
                AppThemePane(onOpenGallery, onOpenPresets = { showPresetBoard = true })
            } else {
                WidgetPane(onOpenGallery)
            }
        }
    }

    // 预设主题板是二级页：盖在外观页上（不是塞进滚动区，否则会被页面滚动带着走）
    if (showPresetBoard) BgPresetBoard { showPresetBoard = false }
}

@Composable
private fun PaneTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) C.violet.copy(alpha = 0.16f) else C.card,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (selected) C.violet.copy(alpha = 0.55f) else C.line,
                RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) C.violet else C.txt2,
        )
    }
}

// ────────────────────────────── App 主题 ──────────────────────────────

@Composable
private fun AppThemePane(
    onOpenGallery: (String, String) -> Unit,
    onOpenPresets: () -> Unit = {},
) {
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

    // 「预览」板块已删（2026-10-18 用户要求）：改主题当场就落到这一页上，
    // 再摆一个假预览框只会让人以为那才是真的。

    // 明暗：三档并排，不再一人一行
    SectionLabel("明暗")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            AppearanceStore.DARK to "深色",
            AppearanceStore.LIGHT to "浅色",
            AppearanceStore.SYSTEM to "跟随系统",
        ).forEach { (value, label) ->
            PaneTab(label, themeChoice == value) { AppearanceStore.setTheme(ctx, value) }
        }
    }
    Spacer(Modifier.height(20.dp))

    AccentSection()
    Spacer(Modifier.height(20.dp))

    SectionLabel("页面背景")
    // 预设主题收进二级页：这一页只留一块"当前长什么样"的预览 + 一个入口。
    // 12 个色板平铺在这里，等于把「选背景」和「选主题色」挤在同一屏，谁都不显眼。
    BgEntryCard(bgChoice, dark = dark) { onOpenPresets() }

    // 图片浓淡：用户会嫌背景图太抢眼、字不好读。只作用于自选照片，不动内置渐变。
    if (photoSet) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("图片浓淡", color = C.txt3, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Slider(
                value = AppearanceStore.bgAlpha.value,
                onValueChange = { AppearanceStore.setBgAlpha(ctx, it) },
                valueRange = 0.15f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = C.violet,
                    activeTrackColor = C.violet,
                    inactiveTrackColor = C.line,
                ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${(AppearanceStore.bgAlpha.value * 100).toInt()}%",
                color = C.txt2, fontSize = 11.sp,
                modifier = Modifier.width(34.dp),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PaneTab("在线图库", false) { onOpenGallery("portrait", "background") }
        PaneTab(
            when {
                busy -> "正在处理…"
                photoSet -> "换一张照片"
                else -> "用自己的照片"
            },
            photoSet,
        ) {
            if (!busy) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        if (photoSet) {
            Text(
                "换回内置背景",
                color = C.txt3,
                fontSize = 10.5.sp,
                modifier = Modifier.clickable {
                    BackgroundPhoto.clear(ctx)
                    AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
                },
            )
        }
    }
    msg?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = C.amber, fontSize = 12.sp, lineHeight = 18.sp)
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "照片走系统选择器，App 不申请存储权限；只存在这台手机上，不上传。",
        color = C.txt3, fontSize = 10.5.sp, lineHeight = 16.sp,
    )

    Spacer(Modifier.height(16.dp))
    Action2("恢复默认外观", "深色 + 默认强调色 + 纯色底，并删掉本机保存的照片") {
        BackgroundPhoto.clear(ctx)
        AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
        AppearanceStore.setTheme(ctx, AppearanceStore.DARK)
        AppearanceStore.setAccent(ctx, AccentSpec.DEFAULT)
    }
}

// ────────────────────────────── 桌面小组件 ──────────────────────────────

@Composable
private fun WidgetPane(onOpenGallery: (String, String) -> Unit) {
    Text(
        "这里改的只是桌面上那张卡的配色。App 里看到什么颜色跟它无关 —— 两边各存各的。",
        color = C.txt2, fontSize = 12.sp, lineHeight = 18.sp,
    )
    Spacer(Modifier.height(16.dp))
    WidgetSection(onOpenGallery = { onOpenGallery("landscape", "widget") })
}

// ────────────────────────────── 公用小件 ──────────────────────────────

/**
 * 真机屏幕顶部那一截，**1:1**。
 *
 * 外框只有 [PREVIEW_H] 高，里面那层按真机比例（[PHONE_ASPECT]）铺满整屏该有的高度，
 * 再把上沿裁出来 —— 所以这里看到的渐变走向、光晕位置、卡片留白，和退出这页之后一模一样。
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

/**
 * 「页面背景」的唯一入口：左边一块当前背景的缩影，右边写名字 + 一个 ›。
 * 点进去是完整的预设主题板（浓郁 + 淡雅合在一起挑）。
 */
@Composable
private fun BgEntryCard(bg: String, dark: Boolean, onClick: () -> Unit) {
    val preset = if (bg.startsWith(BG_PRESET_PREFIX)) presetById(bg.removePrefix(BG_PRESET_PREFIX)) else null
    val name = when {
        preset != null -> preset.name
        bg.startsWith(AppearanceStore.BG_PHOTO) -> "自选图片"
        else -> "纯色"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.card, RoundedCornerShape(14.dp))
            .border(1.dp, C.line, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(96.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, C.lineHi, RoundedCornerShape(9.dp)),
        ) {
            if (preset != null) {
                BgPresetSurface(preset, dark, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF221F2B) else Color(0xFFEFEDF5)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("预设主题", color = C.txt, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("当前：$name · 12 款可选", color = C.txt3, fontSize = 11.sp)
        }
        Text("›", color = C.txt3, fontSize = 20.sp)
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * 预设主题板：把原来的「浓郁 / 淡雅」两节合成一块板（用户原话：整合到一块，就叫预设主题）。
 * 点一下立刻换，不关页 —— 换配色最需要的是来回比。
 */
@Composable
private fun BgPresetBoard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val bgChoice = AppearanceStore.background.value
    val dark = C.bg.luminance() <= 0.5f
    Column(Modifier.fillMaxSize().overlaySurface) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("预设主题", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "关闭", color = C.txt3, fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BgPresets.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { preset ->
                        BgSwatch(
                            preset = preset,
                            dark = dark,
                            selected = bgChoice == BG_PRESET_PREFIX + preset.id,
                        ) { AppearanceStore.setBackground(ctx, BG_PRESET_PREFIX + preset.id) }
                    }
                }
            }
            Text(
                "换完直接生效，不用再点保存。",
                color = C.txt3, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

/**
 * 一块背景色卡：画的是这款背景的**真实渲染**（和背景层同一段绘制代码、同一份遮罩），
 * 不是"代表色" —— 色卡得和真机长得一样才有意义。
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
            fontSize = 11.sp,
        )
    }
}

private val PREVIEW_H = 190.dp

/** 390×844 是参考机型（1080×2340、density 2.75）的 dp 尺寸，预览按这个比例裁 */
private const val PHONE_ASPECT = 390f / 844f

/** 小节标题：比正文暗一档的小字，全页统一 */
@Composable
private fun SectionLabel(text: String) {
    Text(text, color = C.txt3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Action2(text: String, hint: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp)) {
        Text(text, color = C.txt.copy(alpha = 0.88f), fontSize = 14.sp)
        hint?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, color = C.txt3, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
