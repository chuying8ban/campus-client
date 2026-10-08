package top.ccbase.campus.ui.me

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.school.SchoolImportFlow
import top.ccbase.campus.ui.theme.overlaySurface

/**
 * 「导入我的教务课表」—— 官方 HTTPS WebView 登录，默认只导入本机。
 *
 * 旧版的「填学号密码 + 上传 activities」路径已退役：登录发生在学校官方页面，
 * App 不读取密码、不把学号/密码/Cookie 发给自己的服务器。导入完成后可以显式选择
 * 是否同步到云端；默认什么都不发。
 *
 * 整屏浮层必须自己画不透明底色（否则下层的「我的」会透上来，2026-09-18 真机出过叠字）。
 *
 * @param onRelogin 保留给旧调用点：本地导入不再依赖云端令牌，因此正常情况下不会触发。
 */
@Composable
fun CrawlScreen(
    db: CampusDb,
    api: CampusApi,
    onClose: () -> Unit,
    onRelogin: () -> Unit = {},
    watchdogMs: Long = SyncLogic.WATCHDOG_MS,
) {
    Box(Modifier.fillMaxSize().overlaySurface) {
        SchoolImportFlow(
            db = db,
            api = api,
            onDone = onClose,
            onCancel = onClose,
        )
    }
}
