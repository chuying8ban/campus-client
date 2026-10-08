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
 * **这一页的每一句都必须是真的、可核对的**，改代码时如果处理方式变了，这里必须同步改
 * —— 写一句做不到的承诺比不写更伤信任。现在的实现（credential-free）：
 *   · 密码只在**学校官方 HTTPS 登录页**里输入，登录发生在 WebView 里的官方 origin
 *   · App 不读取密码 DOM、不保存密码、**不把密码/Cookie 发给我们的服务器**
 *   · 只从登录后的官方页面白名单提取课表字段（courseName/weekday/startUnit…）
 *   · 导入结束即清教务登录状态：Cookie（含 /student 路径）+ WebStorage + 页面缓存
 *   · App 自己的云端令牌与教务 Cookie 是两回事，分开处理
 *   · 一键删除：旧版本存过服务器教务密码的，可在「我的」里删掉（`DELETE /api/v2/credentials`，带自己的登录令牌）
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
            top.ccbase.campus.school.ImportPrivacy.SUMMARY,
            color = C.txt2, fontSize = 12.5.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))

        Block("你的密码怎么被处理", listOf(
            "密码只在学校官方的登录页里输入（HTTPS），App 只负责把这一页显示出来。",
            "是否显示密码由学校页面控制；App 不读取密码框、不保存密码，也不会把学号/密码/Cookie 发给我们的服务器。",
            "导入完成或取消后执行清理：教务登录的 Cookie（含 /student 路径）、网页存储和页面缓存都会被清掉。",
            "本地保存默认不上传；只有阅读并同意云端条款后，才发送清洗后的课表字段。",
        ))

        Block("它只被送到哪儿", listOf(
            "只到学校教务系统自己的 HTTPS 地址 https://eams.cupk.edu.cn —— 与其他网站无关。",
            "登录状态只在本次导入期间存在，导入结束（完成或取消）随即清理。",
            "同步云端时只上传课表字段（课程名、星期、节次、周次、教室、教师），不上传密码或 Cookie。",
        ))

        Block("云端同步说明与同意", listOf(top.ccbase.campus.school.ImportPrivacy.TERMS))

        Block("你可以随时做的事", listOf(
            "一键删除：旧版本在服务器上存过教务密码的，在「我的」→「删除服务器上的旧教务密码」删掉。",
            "想核实连接安全：用「网络自检」看解析到的 IP 和 443 端口；全程 HTTPS。",
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
                "登录页是学校官方的，我们看不到你在里面输入了什么；能看到的只有登录后公开给你自己的课表。" +
                    "即便如此：别在教务系统里用和银行、邮箱等重要账号相同的密码。",
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
