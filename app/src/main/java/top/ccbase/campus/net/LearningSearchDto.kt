package top.ccbase.campus.net
import kotlinx.serialization.Serializable
@Serializable data class LearningSearchResult(val query: String = "", val items: List<LearningSearchItem> = emptyList(), val elapsed_ms: Int = 0, val cached: Boolean = false, val partial: Boolean = false, val provider: String = "", val notice: String = "")
@Serializable data class LearningSearchItem(val title: String, val url: String, val snippet: String = "", val source: String = "", val kind: String = "document", val signals: List<String> = emptyList())
