package app.shimpyo.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RestStop(val name: String, val point: GeoPoint, val kind: Kind) {
    enum class Kind { SERVICE_AREA, REST_AREA }
}

object Geo {
    private const val EARTH_KM = 6371.0

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_KM * asin(sqrt(h))
    }

    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}

/** 진행 방향 앞쪽(±[maxAngleDeg])에 있는 가장 가까운 쉼터를 고른다. */
object RestStopSelector {
    data class Pick(val stop: RestStop, val distanceKm: Double)

    fun nextAhead(
        stops: List<RestStop>,
        location: CarLocation,
        minKm: Double = 1.0,
        maxKm: Double = 40.0,
        maxAngleDeg: Double = 60.0,
    ): Pick? = stops.asSequence()
        .map { it to Geo.distanceKm(location.point, it.point) }
        .filter { (_, d) -> d in minKm..maxKm }
        .filter { (s, _) ->
            Geo.angleDiff(Geo.bearingDeg(location.point, s.point), location.headingDeg) <= maxAngleDeg
        }
        .minByOrNull { (_, d) -> d }
        ?.let { (s, d) -> Pick(s, d) }
}
