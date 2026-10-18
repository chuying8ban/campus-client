package top.ccbase.campus.alarm

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 勿扰（模式）访问的"占位服务" —— 它**不执行任何逻辑**，存在的唯一目的是
 * 让系统把校园助手列进「模式访问权限 / 勿扰访问」那份名单。
 *
 * 为什么必须有：Android 判断"哪些应用可以申请勿扰访问"看的是清单里有没有一个
 * 声明了 `android.permission.BIND_NOTIFICATION_POLICY_ACCESS_SERVICE` 的 Service。
 * 只加 `ACCESS_NOTIFICATION_POLICY` 权限、不声明这个服务，结果就是——
 * 用户点「去授权」，跳过去的列表里**根本没有这个应用**，权限永远授不上，
 * 自动静音一直显示"缺：勿扰访问"。（2026-10-18 用户截图实测到的就是这个。）
 *
 * 真正改铃声模式的是 `AudioManager.ringerMode`，走的是同一个 policy access 授权，
 * 不需要这个服务参与；它只负责"出现在名单里"。
 */
class PolicyAccessService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
