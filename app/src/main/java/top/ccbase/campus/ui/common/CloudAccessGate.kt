package top.ccbase.campus.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.material3.*

/** 仅提供明确导航，不在用户同意前注册身份或上传数据。 */
@Composable
fun CloudAccessGate(onClose: () -> Unit, onImport: () -> Unit) {
    BackHandler { onClose() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("此功能需要云端同步") },
        text = { Text("请先导入课表，并在导入结果页阅读条款、选择同意同步到云端。仅本机使用也可以继续查看课表和使用本地功能；此处不会自动创建账号或上传数据。") },
        confirmButton = { TextButton(onClick = onImport) { Text("去导入与同步") } },
        dismissButton = { TextButton(onClick = onClose) { Text("继续本机使用") } },
    )
}
