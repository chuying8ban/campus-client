package top.ccbase.campus.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.ccbase.campus.ui.theme.C
import top.ccbase.campus.ui.theme.overlaySurface

/**
 * 「安全与隐私」整页声明 —— 用户第一句问的是「我凭什么把教务密码给你」，
 * 光在输密码那一行写一句不够。这里把「密码怎么被处理」逐条讲清楚。
 *
 * **这一页的每一句都必须是真的、可核对的**，改代码时如果加密方式/权限变了，
 * 这里必须同步改 —— 写一句做不到的承诺比不写更伤信任。对应实现：
 *   · AES-GCM 加密：server `multiuser.encrypt/decrypt`（带认证，密文被改解密即失败）
 *   · 密钥单独文件、权限 600、不进代码仓库：`multiuser.load_key`（权限不对直接拒绝启动）
 *   · 登录令牌只存 sha256：`multiuser.token_hash`（库被看光也拿不到有效会话）
 *   · 每人的数据按 uid 隔离、接口只认自己的令牌：`multiuser_api.current_user` + `mu.scoped`
 *   · 密码不以明文保存：服务端加密入库（AES-GCM）；本机保存/读取路径都不打印内容
 *   · 全程 HTTPS：80 → 301 → 443
 *   · 一键删除：`POST /api/v2/credentials/delete`（服务端把 cred_enc 置 NULL）
 */
@Composable
fun SafetyScreen(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .overlaySurface
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("安全与隐私", color = C.txt, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("关闭", color = C.txt3, fontSize = 13.sp, modifier = Modifier.clickable { onClose() })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "你在这里输入的教务密码，是我们唯一会碰到的敏感信息。下面把它怎么被处理逐条写清楚。",
            color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))

        Block("你的密码怎么被处理", listOf(
            "输入时不显示明文；密码在服务器上加密保存，仅用于代你登录教务。服务端提供查看能力，但那个入口只对作者本人开放，普通用户看不到。",
            "存进服务器前用 AES-GCM 加密（带完整性校验：密文被改动过会直接解密失败，不会悄悄返回错的东西）。",
            "加密密钥单独放在一个文件里，权限 600、只有服务器管理员可读，不进代码仓库；权限不对服务直接拒绝启动。",
            "密码不以明文保存：服务端加密入库；本机保存与读取这两条路径都不打印内容。",
        ))

        Block("它只被用来做一件事", listOf(
            "用你的账号登录教务系统：读你的课表、生成你的学习规划、盯着你指定的课有没有余位，并在有余位时提醒你。",
            "除此之外不做任何操作，不碰你的成绩单、不查你的其他信息、不会用你的账号做别的事。",
            "只有你自己能读回自己的信息：每个接口的 uid 都由你的登录令牌推导，客户端传什么都不认。",
            "同学之间互相看不到对方的课表、任务和账号。",
        ))

        Block("你可以随时做的事", listOf(
            "一键删除：本页下方「我的」→「删除服务器上保存的密码」，删掉后只保留已经生成好的规划。",
            "想核实连接安全：用「网络自检」看解析到的 IP 和 443 端口；App 与服务器之间全程 HTTPS。",
            "查看当前授权：在「授权与白名单」里能看到通知、闹钟、电池白名单这些是否都开了。",
        ))

        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.card)
                .border(1.dp, C.line, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("一句诚实的话", color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "密钥在服务器上，所以服务器管理员在技术上可以解密 —— 这也是我们不做「回显密码」功能的原因。" +
                    "所以：别用和银行、邮箱等重要账号相同的密码。",
                color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Block(title: String, items: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(C.card)
            .border(1.dp, C.line, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, color = C.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        items.forEach { t ->
            Row(verticalAlignment = Alignment.Top) {
                Text("·", color = C.txt3, fontSize = 12.5.sp)
                Spacer(Modifier.height(0.dp).padding(start = 6.dp))
                Text(t, color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
