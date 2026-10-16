package top.ccbase.campus.school

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class AuthResourceDiagnosticsTest {
 @Test fun `resource errors retain only fixed category and numeric status`() {
  val d=AuthResourceDiagnostics()
  d.http("https://secret.example/person/123/app.js?ticket=PRIVATE",404,false)
  d.network("https://secret.example/api?password=PRIVATE",-2,false)
  d.http("https://secret.example/",200,true)
  assertEquals(listOf("http:script:404","network:other:-2"),d.snapshot())
  assertFalse(d.snapshot().toString().contains("PRIVATE"))
  for(n in 400..450)d.http("https://secret.example/",n,false)
  assertEquals(16,d.snapshot().size)
 }
 @Test fun `JS error types cannot export arbitrary names or server fields`() {
  val result=JsonPrimitive("""{"tables":0,"frames":0,"hasTreeModel":false,"ready":true,"jsTypes":["TypeError","PRIVATE","ReferenceError"]}""").toString()
  assertEquals("TypeError,ReferenceError",AuthResourceDiagnostics.jsTypes(result))
  val payload=SchoolDiagnosticSender.payload("00000000-0000-0000-0000-000000000001","neau",NeauImport.entry,result,emptyList())
  assertEquals(setOf("id","school","version","page","tables","frames","hasTreeModel","ready","events"),Json.parseToJsonElement(payload).jsonObject.keys)
  assertFalse(payload.contains("jsTypes"))
 }
}
