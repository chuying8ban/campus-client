package top.ccbase.campus.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.ccbase.campus.alarm.DiagItem
import top.ccbase.campus.alarm.DiagLog
import top.ccbase.campus.alarm.DiagSnapshot
import top.ccbase.campus.alarm.SilenceDiag
import top.ccbase.campus.ui.theme.C

/**
 * 「授权与白名单」页 —— 把「App 需要的系统授权」一次说清、并且**在 App 内直接去要**。
 *
 * 为什么要有它（而不是只靠网络自检里那几行）：
 *   自检是「告诉你缺什么」。用户在户外看到一行「缺少勿扰访问权限」，
 *   还是得自己去翻设置 —— 小米/HyperOS 里那一项藏在「设置 → 通知与控制中心 → 勿扰 → 拉到底」，
 *   很多人翻五分钟也找不到，然后这个功能就永远是坏的。
 *   这一页把三件事凑齐：
 *     ① 缺哪几项、**缺了会怎样**（只写"未授权"等于没写）
 *     ② **App 内直接去要**：能弹系统窗的弹窗（通知、电池不优化），弹不了的一步跳到那一页
 *     ③ 厂商自己的后台限制（自启动/省电策略/加锁）—— 这些系统**查不到状态**，只给照着做的路径
 *
 * 状态**现读**，每 1.5 秒刷一次：用户去系统设置点完开关返回，这一页必须自己变对，
 * 不能让他手动刷新（他很可能就停在半路上，然后以为 App 又坏了）。
 */
@Composable
fun PermissionsScreen(
    /** 现读一份体检快照。抽成参数是为了能在 JVM/Robolectric 测试里注入固定值（测试不能读真机状态） */
    readSnapshot: () -> DiagSnapshot,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(readSnapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            snap = readSnapshot()
            delay(1500)
        }
    }

    val items = SilenceDiag.permissionItems(snap)
    val missing = items.filter { !it.ok }
    val guides = SilenceDiag.vendorGuides()

    // 通知权限能在 App 内弹系统窗（Android 13+），其余只能跳设置页
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "授权与白名单",
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭 ✕", fontSize = 12.sp, color = C.txt3,
                    modifier = Modifier.clickable { onClose() },
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                if (missing.isEmpty()) {
                    "全部已授权 —— 课前静音、闹钟提醒、App 更新都能正常工作。"
                } else {
                    "还差 ${missing.size} 项。能弹系统窗的会直接弹窗，其余一步跳到对应的设置页；" +
                        "点完返回这一页会自己刷新。"
                },
                fontSize = 11.sp, lineHeight = 17.sp,
                color = if (missing.isEmpty()) C.cyan else C.amber,
            )

            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                Btn("从第 1 项开始：${missing.first().name}", primary = true) {
                    DiagLog.openItem(ctx, missing.first())
                }
            }

            Spacer(Modifier.height(13.dp))
            items.forEach { item ->
                key(item.name) { PermissionRow(ctx, item, notifPerm) }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Spacer(Modifier.height(12.dp))
            Text(
                "厂商后台限制（系统不提供查询，自己看一眼）",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.txt2,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "小米 / HyperOS 上，下面这三处不给够，闹钟到点不会响 —— 而且不会有任何报错。",
                fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt3,
            )
            Spacer(Modifier.height(8.dp))
            guides.forEach { g ->
                key(g.title) { GuideRow(g.title, g.steps) }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "这一页的状态每 1.5 秒自己读一次，所以去系统设置里点完开关回来就变 ✓。",
                fontSize = 10.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(14.dp))
            Btn("关闭") { onClose() }
        }
    }
}

/** 一行授权项：✗ 时给出「去授权」+ 缺了会怎样 + 怎么开。 */
@Composable
private fun PermissionRow(
    ctx: Context,
    item: DiagItem,
    notifPerm: androidx.activity.result.ActivityResultLauncher<String>,
) {
    var guide by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (item.ok) "✓ " else "✗ ") + item.name,
                fontSize = 12.5.sp,
                color = if (item.ok) C.txt3 else C.amber,
                fontWeight = if (item.ok) FontWeight.Normal else FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (!item.ok) {
                Btn(item.action) {
                    // 通知：Android 13+ 能在 App 内直接弹系统窗，比跳设置页省事
                    val needRuntime = item.name == "通知"
                    if (needRuntime) {
                        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        DiagLog.openItem(ctx, item)
                    }
                }
            }
        }
        if (!item.ok) {
            Spacer(Modifier.height(2.dp))
            Text(item.detail, fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt3)
            if (item.steps.isNotEmpty()) {
                Text(
                    if (guide) "收起怎么开" else "怎么开 ›",
                    fontSize = 10.5.sp, color = C.cyan,
                    modifier = Modifier.padding(top = 2.dp).clickable { guide = !guide },
                )
                if (guide) {
                    Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.steps.forEachIndexed { i, s ->
                            Text("${i + 1}. $s", fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt2)
                        }
                    }
                }
            }
        }
    }
}

/** 厂商限制的一条：只有路径，没有状态（系统查不到）。 */
@Composable
private fun GuideRow(title: String, steps: List<String>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("· $title", fontSize = 11.5.sp, color = C.txt2)
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) "收起" else "看路径 ›",
                fontSize = 10.5.sp, color = C.cyan,
                modifier = Modifier.clickable { open = !open },
            )
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { Text(it, fontSize = 10.5.sp, lineHeight = 15.sp, color = C.txt3) }
            }
        }
    }
}

/** 这个页面的按钮：没有底色方块，只有文字 + 细边，跟 App 其余部分一致（用户明确反感方框卡片堆砌）。 */
@Composable
private fun Btn(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) C.violet else C.txt2,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
