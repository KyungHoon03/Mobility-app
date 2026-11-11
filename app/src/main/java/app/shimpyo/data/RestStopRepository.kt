package app.shimpyo.data

import android.content.Context
import app.shimpyo.domain.GeoPoint
import app.shimpyo.domain.RestStop
import org.json.JSONArray

/**
 * 고속도로 휴게소·졸음쉼터 좌표. 출처: © OpenStreetMap contributors (ODbL 1.0).
 * assets/rest_stops.json 형식: [{"n":이름,"lat":위도,"lon":경도,"k":"S"(휴게소)|"R"(쉼터)}]
 */
class RestStopRepository(private val context: Context) {
    val stops: List<RestStop> by lazy { load() }

    private fun load(): List<RestStop> = runCatching {
        val text = context.assets.open("rest_stops.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RestStop(
                name = o.getString("n"),
                point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
                kind = if (o.optString("k") == "S") RestStop.Kind.SERVICE_AREA else RestStop.Kind.REST_AREA,
            )
        }
    }.getOrElse { emptyList() }
}
