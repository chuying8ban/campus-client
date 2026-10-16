package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
class AustLoginPolicyTest {
 @Test fun `仅允许学校认证HTTPS域`(){assertTrue(AustLoginPolicy.allowed(AustLoginPolicy.ENTRY));listOf("http://authserver.aust.edu.cn/authserver/login","https://authserver.aust.edu.cn.evil.org/","https://user@authserver.aust.edu.cn/","https://authserver.aust.edu.cn:444/","https://jwgl.aust.edu.cn/").forEach{assertFalse(AustLoginPolicy.allowed(it))}}
}
