package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
class SchoolDiagnosticsTest {
 @Test fun `路径查询与会话身份均脱敏`(){val s=SchoolDiagnostics.location("https://authserver.aust.edu.cn/authserver/login;jsessionid=secret?ticket=secret#student123");assertEquals("https://authserver.aust.edu.cn/authserver/login",s);assertFalse(SchoolDiagnostics.location("https://example.org/2026000000/tokenvalue").contains("2026000000"))}
 @Test fun `重复脱敏保持有效HTTPS地址`(){val a=SchoolDiagnostics.location("https://authserver.aust.edu.cn/authserver/mfa/123456?token=secret");assertEquals(a,SchoolDiagnostics.location(a));assertTrue(a.startsWith("https://"));assertFalse(a.contains("123456"))}
 @Test fun `只输出结构白名单`(){val s=SchoolDiagnostics.signals("\"{\\\"tables\\\":2,\\\"frames\\\":1,\\\"hasTreeModel\\\":false,\\\"ready\\\":true,\\\"password\\\":\\\"dummy\\\"}\"");assertTrue(s.contains("表格数量：2"));assertFalse(s.contains("dummy"))}
 @Test fun `异常或越界信号拒绝`(){listOf("null","\"{}\"","\"{\\\"tables\\\":999,\\\"frames\\\":1,\\\"hasTreeModel\\\":false,\\\"ready\\\":true}\"").forEach{assertThrows(Exception::class.java){SchoolDiagnostics.signals(it)}}}
 @Test fun `脚本不读敏感内容也不联网`(){listOf("innerHTML","innerText","textContent","cookie","fetch(","XMLHttpRequest","querySelector('input","console").forEach{assertFalse(it,SchoolDiagnostics.script.contains(it))}}
}
