package top.ccbase.campus.update
import org.junit.Test
import org.junit.Assert.*
import top.ccbase.campus.BuildConfig
class SchoolTestChannelTest {
 @Test fun `测试通道与常规云端及正式通道分离`() {
  if(BuildConfig.APPLICATION_ID.endsWith(".privacytest")) {
   assertEquals("https://example.invalid",BuildConfig.API_BASE)
   assertEquals("https://study.ccbase.top/updates/school-test/manifest.json",Updater.manifestUrl(BuildConfig.UPDATE_BASE))
   assertTrue(BuildConfig.VERSION_CODE>89)
  } else assertEquals(BuildConfig.API_BASE+"/updates/manifest.json",Updater.manifestUrl(BuildConfig.UPDATE_BASE))
 }
}
