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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.ccbase.campus.ui.theme.AppearanceStore
import top.ccbase.campus.ui.theme.BG_PRESET_PREFIX
import top.ccbase.campus.ui.theme.BackgroundPhoto
import top.ccbase.campus.ui.theme.BgPresets
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.brush
import top.ccbase.campus.ui.theme.overlaySurface
import top.ccbase.campus.ui.theme.presetById

/**
 * 「外观」—— 主题三档 + 背景图三层。
 *
 * 两条对用户的承诺，代码必须让它们为真（`AppearanceGuardTest` 会扫源码守住）：
 *  1. **不申请任何存储权限**：选图走系统照片选择器（`PickVisualMedia`），
 *     App 只拿到你挑中的那一张的读权限；这条路上"不申请"才是正确答案，申请了反而多余。
 *  2. **图片只存本机、不上传**：写进 `filesDir`，这一整块代码里没有任何网络调用。
 *
 * 为什么背景图要单独一层而不是做成"主题"之一：主题决定**文字与面板的颜色**，
 * 背景图决定**底下铺什么**。两者正交 —— 浅色主题配深色渐变时，遮罩（`SCRIM_ALPHA`）
 * 会把渐变压成很淡的底，观感仍是干净的浅色。
 */
@Composable
fun AppearanceScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeChoice = AppearanceStore.theme.value      // 读快照：改完当场反映
    val bgChoice = AppearanceStore.background.value
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

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
            "主题决定文字和面板的颜色，背景图单独一层铺在底下。设置只存在这台手机上。",
            color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))

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
        Text("内置背景（程序生成的渐变，不带图片文件）", color = C.txt3, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        // 一排色卡：点哪张就用哪张。缩略图直接画那个渐变，所见即所得。
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BgPresets.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { preset ->
                        val selected = bgChoice == BG_PRESET_PREFIX + preset.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(92.dp)
                                .clickable { AppearanceStore.setBackground(ctx, BG_PRESET_PREFIX + preset.id) },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .background(preset.brush(), RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) C.violet else C.lineHi,
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                preset.name + if (selected) " ✓" else "",
                                color = if (selected) C.violet else C.txt2,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ChoiceRow(
            if (busy) "正在处理这张图…" else "自己上传一张",
            selected = bgChoice.startsWith(AppearanceStore.BG_PHOTO),
            enabled = !busy,
        ) {
            // 走系统照片选择器：不申请存储权限，App 只拿到这一张的读权限
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        Spacer(Modifier.height(10.dp))
        msg?.let {
            Text(it, color = C.amber, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
        }
        Action2("恢复默认外观", "深色主题 + 纯色底，并删掉本机保存的那张图") {
            BackgroundPhoto.clear(ctx)
            AppearanceStore.setBackground(ctx, AppearanceStore.BG_SOLID)
            AppearanceStore.setTheme(ctx, AppearanceStore.DARK)
        }

        Spacer(Modifier.height(14.dp))
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
            ).forEach {
                Text("· $it", color = C.txt2, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

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
