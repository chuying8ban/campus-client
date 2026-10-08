package top.ccbase.campus

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.material3.*

@Composable
fun ExitConfirmation(onExit: () -> Unit, content: @Composable () -> Unit) {
    var askExit by remember { mutableStateOf(false) }
    BackHandler { askExit = true }
    content()
    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("退出校园助手？") },
        text = { Text("退出不会删除已保存的数据。提醒是否正常送达仍受系统权限和省电设置影响。") },
        confirmButton = { TextButton(onClick = { askExit = false; onExit() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { askExit = false }) { Text("继续使用") } },
    )
}
