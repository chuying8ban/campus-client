package top.ccbase.campus.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.ccbase.campus.ui.theme.C

/**
 * App 内的更新说明（用户 2026-10-19："新版本内容通知要在 app 内出现"）。
 *
 * 为什么不用系统通知就完事：通知会被划掉、被勿扰吞掉、被"清理"一波带走，
 * 用户装完新版根本不知道改了什么。这里在 App 里直接弹一次，点「知道了」才记已读。
 * 数据来自随包的 `assets/changelog.tsv`，和系统通知同源（见 [top.ccbase.campus.update.UpdateNotice]）。
 */
@Composable
fun UpdateNotesDialog(version: String, body: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(C.card)
                .border(1.dp, C.line, RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(7.dp).background(C.violet, RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.size(9.dp))
                Text("已更新到 $version", color = C.txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text("这一版改了什么", color = C.txt3, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))

            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                body.lines().filter { it.isNotBlank() }.forEach { line ->
                    Text("· ${line.trim()}", color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(C.violet.copy(alpha = 0.20f))
                    .border(1.dp, C.violet.copy(alpha = 0.55f), RoundedCornerShape(13.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", color = C.violet, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
