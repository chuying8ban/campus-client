package top.ccbase.campus.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.ccbase.campus.ui.theme.C

/**
 * App 内的更新说明（用户 2026-10-19："新版本内容通知要在 app 内出现"）。
 *
 * 第二版用户截图「通知的显示有问题」：卡片是半透明的，底下的课表（"大学英语"、教室、老师）
 * 整片透上来，标题和正文互相压着，看着像渲染坏了。
 *
 * 原因是色板里的 `card` 自带 alpha（本来要让壁纸透出来），直接铺进 Dialog 就等于
 * 连背景一起透。改成三层：
 *   1. 整屏压一层深色蒙（挡住内容，也挡住误触）；
 *   2. 卡片 = 不透明深色打底 + `card` 叠加（`compositeOver`），有主题色相但不透；
 *   3. 正文限高自滚，不再被「知道了」压掉半行。
 *
 * 数据来自随包的 `assets/changelog.tsv`，与系统通知同源（[top.ccbase.campus.update.UpdateNotice]）。
 */
@Composable
fun UpdateNotesDialog(version: String, body: String, onDismiss: () -> Unit) {
    val solid = Color(0xFF0B0910)              // App 的深色底
    val card = C.card.compositeOver(solid)     // 实心卡，保留主题色相

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC8000000))
                .clickable(indication = null, interactionSource = MutableInteractionSource()) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(card)
                    .border(1.dp, C.lineHi, RoundedCornerShape(18.dp))
                    // 吃掉卡片内部的点击，别让"点了下卡片"也关窗
                    .clickable(indication = null, interactionSource = MutableInteractionSource()) {}
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(C.violet, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.size(9.dp))
                    Text("已更新到 $version", color = C.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text("这一版改了什么", color = C.txt3, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    body.lines().filter { it.isNotBlank() }.forEach { line ->
                        Text("· ${line.trim()}", color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(C.violet.copy(alpha = 0.20f).compositeOver(card))
                        .border(1.dp, C.violet.copy(alpha = 0.55f), RoundedCornerShape(13.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("知道了", color = C.violet, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
