package top.ccbase.campus.ui.schedule

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * 顶部「今天已核对课表」那条细带的**下滑自动收起**。
 *
 * 用户 2026-10-21：「最上面提示更新的这个也改成下滑时自动隐藏」。
 *
 * 实现上不去改每个页面：把这一条装在**所有标签页的父级**（CampusApp 里那层 Box），
 * 用 [NestedScrollConnection] 收手势 —— LazyColumn 也好、verticalScroll 也好，
 * 滚动都会先经过父级的 `onPreScroll`，一处接线全站生效，以后加新页面也不用记得再挂一次。
 *
 * 判据是**累计位移**而不是单次增量：手指抖一下就出手指抖一下就回来，会看着像闪屏。
 *   · 往下看内容（手指上滑，`available.y < 0`）→ 累计超过 [HIDE_AFTER_PX] 才收起；
 *   · 往回翻（`available.y > 0`）→ 累计退回去，回到起点附近就重新出现。
 * 收到 `available` 但**不消费**（返回 Offset.Zero）：只旁观，滚动照旧由子级处理。
 */
class BannerAutoHideState {

    /** 现在该不该收起。 */
    var hidden by mutableStateOf(false)
        private set

    /** 累计“已经往下看了多远”（px），用来做迟滞，避免抖动。 */
    private var acc = 0f

    fun onUserScroll(dy: Float) {
        if (dy == 0f) return
        // dy < 0：手势往上推，内容往上走 = 正在往后看 → 累计增加
        // dy > 0：往回翻 → 累计减少
        acc = (acc - dy).coerceIn(0f, HIDE_AFTER_PX * 3f)
        // 两个阈值构成迟滞：收起要滑过 40px，露出得退回 12px 以内。
        // 只用同一个阈值的话，收起后往回抖 15px 就弹出来，看着像闪屏。
        hidden = if (hidden) acc > SHOW_BACK_PX else acc > HIDE_AFTER_PX
    }

    /** 切标签页、回到首页时复位：新页面的顶部本来就该看到它。 */
    fun reset() {
        acc = 0f
        hidden = false
    }

    /** 交给父级 `Modifier.nestedScroll(...)` 用。 */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 只认手指拖出来的位移；惯性和代码滚动不算（否则一进页面自动滚一下它就没了）
            if (source == NestedScrollSource.UserInput) onUserScroll(available.y)
            return Offset.Zero
        }
    }

    companion object {
        /** 收起阈值：约 40px，滑动一屏高度的一小截就够，不至于刚碰一下就消失。 */
        const val HIDE_AFTER_PX = 40f

        /** 露出阈值：已经收起后，得退回这么近（约 12px）才重新出现。 */
        const val SHOW_BACK_PX = 12f
    }
}

@Composable
fun rememberBannerAutoHide(): BannerAutoHideState = remember { BannerAutoHideState() }

/**
 * 顶部核对条 + 自动收起。
 *
 * 用 expand/shrink 而不是纯淡出：收起时要真的把位置让出来，
 * 否则下面那屏会留一条空白（用户对"位置没让出来"这类残留很敏感）。
 */
@Composable
fun AutoHideDailyCheckBanner(state: BannerAutoHideState, ctx: Context, onUpdate: () -> Unit) {
    AnimatedVisibility(
        visible = !state.hidden,
        enter = expandVertically(animationSpec = tween(180)),
        exit = shrinkVertically(animationSpec = tween(180)),
    ) {
        Column { DailyCheckBanner(ctx, onUpdate) }
    }
}
