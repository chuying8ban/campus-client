package top.ccbase.campus.ui
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class LearningTabTest {
 @Test fun `任务系统入口移除收藏页独立接线`() {
  val app=File("src/main/java/top/ccbase/campus/ui/CampusApp.kt").readText()
  assertFalse(app.contains("TASKS("));assertFalse(app.contains("PlanScreen("));assertFalse(app.contains("TasksLearnScreen("))
  val hub=File("src/main/java/top/ccbase/campus/ui/library/LearningHub.kt").readText()
  assertTrue(hub.contains("FavoritesScreen(db)"));assertTrue(hub.contains("我的收藏"));assertFalse(hub.contains("onOpenPlan"))
  val today=File("src/main/java/top/ccbase/campus/ui/today/TodayScreen.kt").readText()
  assertFalse(today.contains("SectionTitle(\"今日任务\""));assertFalse(today.contains("SectionTitle(\"待办\""))
 }
}
