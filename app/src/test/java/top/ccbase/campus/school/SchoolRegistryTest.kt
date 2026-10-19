package top.ccbase.campus.school
import org.junit.Assert.*
import org.junit.Test
class SchoolRegistryTest {
 @Test fun `学校检索不把全国列表当已适配`() {
  assertEquals(listOf(SchoolRegistry.cupk),SchoolRegistry.search("CUPK"))
  assertTrue(SchoolRegistry.search("不存在的大学").isEmpty())
 }
 @Test fun `学校地址严格隔离`() {
  val p=SchoolRegistry.cupk
  assertTrue(p.allows(p.origin+p.loginPath))
  listOf("http://eams.cupk.edu.cn/student/login","https://eams.cupk.edu.cn.evil.org/","https://user@eams.cupk.edu.cn/","https://eams.cupk.edu.cn:444/").forEach{assertFalse(it,p.allows(it))}
  val other=p.copy(id="synthetic",origin="https://synthetic.example.org")
  assertFalse(other.allows(p.origin+p.loginPath))
  assertTrue(other.allows("https://synthetic.example.org/student/login"))
 }
 @Test fun `融合门户是主入口且只多信任一个 CAS 域名`() {
  val p=SchoolRegistry.cupk
  // 主入口走统一身份认证
  assertTrue(p.loginPath, p.loginPath.contains("/sso/login"))
  assertEquals("https://eams.cupk.edu.cn/student/login", p.backupLoginUrl)
  // CAS 必须在白名单里，否则一跳过去就被拦
  assertTrue(p.allows("https://cas.cupk.edu.cn/cas/login?service=x"))
  // 但白名单只多这一个域名：别的 *.cupk.edu.cn 仍然不放行
  assertFalse(p.allows("https://id.cupk.edu.cn/cas/login"))
  assertFalse(p.allows("https://cas.cupk.edu.cn.evil.org/cas/login"))
  assertFalse(p.allows("http://cas.cupk.edu.cn/cas/login"))
 }
 @Test fun `CAS 自动填充只填不提交`() {
  val js=CredentialVault.ssoAutofillJs("s1","p1")
  // 有验证码框时返回 filled-need-captcha，代码里那条分支绝不能提交
  assertTrue(js.contains("filled-need-captcha"))
  assertTrue(js.contains("getElementById('captcha')"))
  // 用户名/密码都写进脚本（只在用户勾了记住密码时才会走到这里）
  assertTrue(js.contains("s1") && js.contains("p1"))
 }
 @Test fun `树维转换保留课表字段删除凭据`() {
  val result=ShuweiStudentAdapter.normalizedActivities("""{"studentTableVms":[{"activities":[{"courseName":"合成课程","weekday":1,"startUnit":1,"endUnit":2,"weekIndexes":[1,3,5],"password":"dummy","teachers":[{"name":"示例教师","cookie":"dummy"}]}]}]}""")
  assertTrue(result.contains("weekIndexes"));assertTrue(result.contains("示例教师"));assertFalse(result.contains("dummy"));assertFalse(result.contains("password"))
 }
 @Test fun `取数脚本不允许外域或脚本注入`() {
  listOf("https://evil.org/", "//evil.org/student/", "/student/../other", "/student/x'bad", "/student/x\n").forEach{path->assertThrows(IllegalArgumentException::class.java){ShuweiStudentAdapter.fetchScript(path)}}
  assertTrue(ShuweiStudentAdapter.fetchScript(ShuweiStudentAdapter.printDataPath(1)).contains("same-origin"))
 }
 @Test fun `未登记学校不允许伪装适配`() {
  assertThrows(IllegalArgumentException::class.java){SchoolRegistry.adapter(SchoolRegistry.cupk.copy(id="other"))}
  assertThrows(IllegalArgumentException::class.java){ShuweiStudentAdapter.normalizedActivities("{\"activities\":[]}")}
 }
}
