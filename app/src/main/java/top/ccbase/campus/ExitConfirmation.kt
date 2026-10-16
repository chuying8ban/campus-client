package top.ccbase.campus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.ccbase.campus.ui.theme.C

/** 提示条常驻时长：超过这段时间，返回重新从「第一次」算起。 */
internal const val EXIT_ARMED_MS = 2_000L

/**
 * 退出确认（只接「根页面且没人处理」的系统返回）。
 *
 * 规矩沿用户反馈改过两次，改前先读：
 *  1. 子页面、弹窗、WebView 历史各自先按语义返回 —— 它们自己的 `BackHandler` 会先被
 *     调度器叫到，这里**只兜底根页面**。别在这里去判断当前在哪个页面。
 *  2. **根页面第一次返回不再弹对话框**。2026-10-16 同学反馈（华为手势导航）：
 *     「从界面划一下总是提示退出应用有点麻烦了」。华为的侧滑返回就是返回手势，
 *     一划就弹一个要点一下才能走的框 —— 误触成本比「偶尔被踢回桌面」高得多。
 *     现在改成：第一次返回只浮出一条**自动消失**的轻提示，2 秒内再返回才真的退出。
 *     （同时保留了「不能一点就掉回桌面」这条老要求的意图。）
 */
@Composable
fun ExitConfirmation(onExit: () -> Unit, content: @Composable () -> Unit) {
    var armed by remember { mutableStateOf(false) }

    BackHandler { if (armed) onExit() else armed = true }

    // 提示条自己会走：2 秒后把「已武装」收回，免得几分钟后无意按一下就被踢出去。
    LaunchedEffect(armed) {
        if (armed) {
            delay(EXIT_ARMED_MS)
            armed = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        if (armed) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(C.cardHi, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("再按一次「返回」退出", color = C.txt2, fontSize = 12.sp)
            }
        }
    }
}
