package top.ccbase.campus

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import top.ccbase.campus.alarm.NudgePrefs
import top.ccbase.campus.alarm.Notify
import top.ccbase.campus.ui.CampusApp
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 全 App 只有一个 Activity（单 Activity + Compose 导航）——
 * 这是 Compose 的推荐结构：转屏、返回栈、深链都由 Compose 侧管，
 * 原生侧不再散落多个 Activity。
 */
class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationsOnce()
        setContent {
            CampusTheme {
                CampusApp(version = BuildConfig.VERSION_NAME)
            }
        }
    }

    /**
     * 通知权限**只问一次**（首次启动）。
     *
     * 为什么要主动问：课前提醒是本地通知，Android 13+ 没给「通知」权限时
     * 系统会把通知**静默丢掉** —— 用户只会觉得"这功能没用"，
     * 而不会想到是自己没点过允许。问一次，比事后让他去设置里翻强。
     *
     * 为什么不每次启动都问：反复弹系统授权框比不给提醒还烦；
     * 之后想补授权，走「我的 → 授权与白名单 / 课前提醒那条的『去授权』」。
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return          // 12 及以下不用申请，装了就能弹
        if (NudgePrefs.notifAsked(this)) return
        if (!NudgePrefs.remindEnabled(this)) return
        if (Notify.allowed(this)) return
        NudgePrefs.setNotifAsked(this, true)
        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
