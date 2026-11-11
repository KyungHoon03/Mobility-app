package app.shimpyo.data

import android.content.Context
import app.shimpyo.domain.Condition
import app.shimpyo.domain.Profile
import app.shimpyo.domain.Report
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val profiles: List<Profile> = listOf(Profile("driver", "운전자"), Profile("p1", "동승자 A"), Profile("p2", "동승자 B")),
    /** 멀미 기록을 연결할 동승자(출발 전 정차 상태에서 고름). */
    val passengerProfileId: String? = "p1",
    val caffeineOk: Boolean = true,
)

/**
 * 기록은 차 안(앱 내부 저장소)에만 저장한다. 서버로 보내지 않는다.
 */
class RecordStore(context: Context) {
    private val file = File(context.filesDir, "shimpyo.json")

    @Synchronized
    fun load(): Pair<Settings, List<Report>> {
        if (!file.exists()) return Settings() to emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val s = root.optJSONObject("settings")
            val profiles = s?.optJSONArray("profiles")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Profile(it.getString("id"), it.getString("name")) } }
            } ?: Settings().profiles
            val settings = Settings(
                profiles = profiles,
                passengerProfileId = s?.optString("passengerProfileId")?.takeIf { it.isNotEmpty() },
                caffeineOk = s?.optBoolean("caffeineOk", true) ?: true,
            )
            val reports = root.optJSONArray("reports")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let {
                        Report(
                            timeMs = it.getLong("t"),
                            condition = Condition.valueOf(it.getString("c")),
                            profileId = it.getString("p"),
                            score = it.getDouble("s"),
                            minutesSinceStart = it.getDouble("m"),
                        )
                    }
                }
            } ?: emptyList()
            settings to reports
        }.getOrElse { Settings() to emptyList() }
    }

    @Synchronized
    fun save(settings: Settings, reports: List<Report>) {
        val root = JSONObject()
        root.put("settings", JSONObject().apply {
            put("profiles", JSONArray().apply { settings.profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
            put("passengerProfileId", settings.passengerProfileId ?: "")
            put("caffeineOk", settings.caffeineOk)
        })
        root.put("reports", JSONArray().apply {
            reports.takeLast(500).forEach {
                put(JSONObject().put("t", it.timeMs).put("c", it.condition.name).put("p", it.profileId).put("s", it.score).put("m", it.minutesSinceStart))
            }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
