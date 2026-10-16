package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
class SchoolDiagnosticSenderTest {
 @Test fun `上传白名单拒绝自由文本和会话数据`() {
 val result="\"{\\\"tables\\\":1,\\\"frames\\\":0,\\\"hasTreeModel\\\":false,\\\"ready\\\":true,\\\"cookie\\\":\\\"dummySecret\\\"}\""
 val p=SchoolDiagnosticSender.payload("b4eb244c-fd76-43d7-9da7-cfdbb7b727d0","aust","https://authserver.aust.edu.cn/authserver/login?ticket=dummySecret",result,listOf("unexpected dummySecret","页面开始 · https://authserver.aust.edu.cn/12345678?token=dummySecret"))
 assertFalse(p.contains("dummySecret"));assertFalse(p.contains("12345678"));assertFalse(p.contains("cookie"));assertTrue(p.contains("其他事件"))
 }
}
