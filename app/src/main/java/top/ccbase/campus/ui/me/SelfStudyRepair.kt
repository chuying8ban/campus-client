package top.ccbase.campus.ui.me

import androidx.compose.runtime.*
import androidx.compose.material3.*
import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.seed.SeedLoader
import top.ccbase.campus.alarm.Rescheduler

/** 旧版作息恢复必须由用户确认，不能从教务空列表推断。 */
@Composable
fun SelfStudyRepair(ctx: Context, db: CampusDb) {
    var ask by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { ask = true }, enabled = !busy) { Text("恢复旧版早晚自习") }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("确认采用旧版作息？") },
        text = { Text("这不是教务数据。早自习周一至周五 08:45–09:15，晚自习周日至周四 20:30–22:05；地点为 A 楼 I 区 301。仅适用于与此作息一致的同学，请按学校和班级通知核对。已有自习安排不会覆盖。") },
        confirmButton = { TextButton(onClick = {
            ask = false; busy = true
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) {
                        if (db.dao().selfStudy().first().isNotEmpty()) {
                            "已有自习安排，未覆盖"
                        } else {
                            top.ccbase.campus.data.local.ManualSchedule.save(db, top.ccbase.campus.data.local.ManualSchedule.snapshot(db).copy(selfstudy=SeedLoader.load(ctx).selfstudy))
                            Rescheduler.request(ctx, "selfstudy_repair")
                            "已恢复旧版早晚自习，请核对时间和地点"
                        }
                    }
                } catch (e: Exception) { "恢复失败，请重试" }
                busy = false
            }
        }) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("取消") } },
    )
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, title = { Text("自习安排") }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
}
