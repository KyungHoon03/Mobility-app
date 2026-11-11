package app.shimpyo.data

import android.content.Context
import app.shimpyo.domain.Calibration
import app.shimpyo.domain.CareEvent
import app.shimpyo.domain.Role
import app.shimpyo.domain.SeatPos
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    /** 좌석마다 누가 앉는지(출발 전 정차 중에 고름). 운전석은 항상 운전자. */
    val seats: Map<SeatPos, Role> = mapOf(SeatPos.REAR_LEFT to Role.CHILD),
    val caffeineOk: Boolean = true,
)

data class Records(
    val settings: Settings = Settings(),
    /** "프로필|상태" → 학습된 통 크기 범위. */
    val calibrations: Map<String, Calibration> = emptyMap(),
    val events: List<CareEvent> = emptyList(),
)

/**
 * 기록은 차 안(앱 내부 저장소)에만 저장한다. 서버로 보내지 않는다.
 */
class RecordStore(context: Context) {
    private val file = File(context.filesDir, "shimpyo_v2.json")

    @Synchronized
    fun load(): Records {
        if (!file.exists()) return Records()
        return runCatching {
            val root = JSONObject(file.readText())
            val s = root.optJSONObject("settings")
            val seats = s?.optJSONObject("seats")?.let { o ->
                o.keys().asSequence().mapNotNull { k ->
                    runCatching { SeatPos.valueOf(k) to Role.valueOf(o.getString(k)) }.getOrNull()
                }.toMap()
            } ?: Settings().seats
            val settings = Settings(seats = seats, caffeineOk = s?.optBoolean("caffeineOk", true) ?: true)
            val cal = root.optJSONObject("calibrations")?.let { o ->
                o.keys().asSequence().associateWith { k ->
                    o.getJSONObject(k).let { Calibration(it.getDouble("lo"), it.getDouble("hi"), it.optInt("n")) }
                }
            } ?: emptyMap()
            val events = root.optJSONArray("events")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { CareEvent(it.getLong("t"), it.getString("k"), it.getString("e"), it.getDouble("l")) }
                }
            } ?: emptyList()
            Records(settings, cal, events)
        }.getOrElse { Records() }
    }

    @Synchronized
    fun save(r: Records) {
        val root = JSONObject()
        root.put("settings", JSONObject().apply {
            put("seats", JSONObject().apply { r.settings.seats.forEach { (seat, role) -> put(seat.name, role.name) } })
            put("caffeineOk", r.settings.caffeineOk)
        })
        root.put("calibrations", JSONObject().apply {
            r.calibrations.forEach { (k, c) -> put(k, JSONObject().put("lo", c.lo).put("hi", c.hi).put("n", c.answers)) }
        })
        root.put("events", JSONArray().apply {
            r.events.takeLast(500).forEach { put(JSONObject().put("t", it.timeMs).put("k", it.key).put("e", it.kind).put("l", it.load)) }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
