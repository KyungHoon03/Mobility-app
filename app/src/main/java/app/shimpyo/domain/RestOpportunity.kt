package app.shimpyo.domain

import kotlin.math.max

/**
 * 휴식 기회 예측: 진행 방향 앞쪽 쉼터마다 도착 예상 시간을 구하고,
 * 가장 먼저 힘들어질 사람의 여유(기한) 안에 닿는 곳 중 "마지막 편안한 휴식 기회"를 고른다.
 *
 * 경로 좌표선이 없으므로 도착 시간은 직선거리 × [ROAD_FACTOR] ÷ 현재 속도로 낸 근사치다.
 */
object RestOpportunity {
    const val ROAD_FACTOR = 1.2
    /** 기한보다 이만큼 먼저 닿아야 "편안하게" 쉬었다고 본다. */
    const val BUFFER_MIN = 2.0

    data class StopEta(val stop: RestStop, val distanceKm: Double, val etaMin: Double)

    data class Choice(
        val stop: StopEta,
        /** 이 쉼터 다음 쉼터는 기한 밖이라, 놓치면 힘든 채로 가야 한다. */
        val isLastBeforeDeadline: Boolean,
        /** 기한 안에 닿는 쉼터가 없어 가장 가까운 곳을 고른 경우. */
        val late: Boolean,
        /** 굽은 길에 들어가기 전에 쉬도록 고른 경우(멀미 기준일 때). */
        val beforeCurvy: Boolean = false,
    )

    fun stopsAhead(stops: List<RestStop>, location: CarLocation, speedKph: Double, maxKm: Double = 80.0): List<StopEta> {
        val kmPerMin = max(speedKph, RouteAhead.MIN_SPEED_KPH) / 60.0
        return stops.asSequence()
            .map { it to Geo.distanceKm(location.point, it.point) }
            .filter { (_, d) -> d in 1.0..maxKm }
            .filter { (s, _) -> Geo.angleDiff(Geo.bearingDeg(location.point, s.point), location.headingDeg) <= 60.0 }
            .filter { (s, _) -> !isOppositeSide(s.name, location.headingDeg) }
            .map { (s, d) -> StopEta(s, d, d * ROAD_FACTOR / kmPerMin) }
            .sortedBy { it.etaMin }
            .toList()
    }

    private val NORTH_BOUND = listOf("서울", "인천", "춘천", "구리", "하남", "일산")
    private val SOUTH_BOUND = listOf("부산", "대전", "대구", "목포", "순천", "광주", "통영", "당진", "청주", "천안", "논산")

    /**
     * 휴게소는 방향별로 따로 있다. 이름에 "○○방향"이 있으면 그 목적지가 진행 방향과 반대인지 대략 판단한다.
     * 이름에 방향이 없으면 걸러내지 않는다(어림 규칙이며, 경로 API가 공개되면 경로 위 쉼터로 대체한다).
     */
    fun isOppositeSide(name: String, headingDeg: Double): Boolean {
        val m = Regex("""([가-힣]+)\s*방향""").find(name) ?: return false
        val dest = m.groupValues[1]
        val southbound = headingDeg > 100 && headingDeg < 260
        val northbound = headingDeg < 80 || headingDeg > 280
        return (southbound && NORTH_BOUND.any { dest.contains(it) }) || (northbound && SOUTH_BOUND.any { dest.contains(it) })
    }

    /**
     * @param deadlineMin 가장 먼저 힘들어질 사람의 여유(분).
     * @param preferServiceArea 탑승자분이 기준이면 화장실·매점이 있는 휴게소를 우선한다.
     */
    fun choose(ahead: List<StopEta>, deadlineMin: Double, preferServiceArea: Boolean): Choice? {
        if (ahead.isEmpty()) return null
        val reachable = ahead.filter { it.etaMin <= deadlineMin - BUFFER_MIN }
        if (reachable.isEmpty()) return Choice(ahead.first(), isLastBeforeDeadline = true, late = true)
        val pool = if (preferServiceArea) reachable.filter { it.stop.kind == RestStop.Kind.SERVICE_AREA }.ifEmpty { reachable } else reachable
        val pick = pool.last() // 기한 안에서 가장 멀리 있는 곳 = 마지막 편안한 기회
        val next = ahead.firstOrNull { it.etaMin > pick.etaMin }
        return Choice(pick, isLastBeforeDeadline = next == null || next.etaMin > deadlineMin - BUFFER_MIN, late = false)
    }

    /**
     * 멀미가 기준일 때: 굽은 길이 기한 안에 시작되면, 굽은 길에 들어가기 전에 닿는 마지막 쉼터를 먼저 찾는다.
     * 쉬고 나서 통이 비워진 채로 굽은 길을 지나게 하기 위해서다. 그런 곳이 없으면 [choose]와 같다.
     */
    fun chooseForMotion(ahead: List<StopEta>, deadlineMin: Double, minutesToCurvy: Double?, preferServiceArea: Boolean): Choice? {
        if (minutesToCurvy != null && minutesToCurvy > BUFFER_MIN && minutesToCurvy < deadlineMin) {
            val before = choose(ahead, minutesToCurvy, preferServiceArea)
            if (before != null && !before.late) return before.copy(beforeCurvy = true)
        }
        return choose(ahead, deadlineMin, preferServiceArea)
    }

    /** 이미 고른 쉼터의 도착 예상 시간을 다시 구한다. 지나쳤으면 null. */
    fun track(stop: RestStop, location: CarLocation, speedKph: Double): StopEta? {
        val d = Geo.distanceKm(location.point, stop.point)
        val angle = Geo.angleDiff(Geo.bearingDeg(location.point, stop.point), location.headingDeg)
        if (d < 0.5 || angle > 100.0) return null
        return StopEta(stop, d, d * ROAD_FACTOR / (max(speedKph, RouteAhead.MIN_SPEED_KPH) / 60.0))
    }

    /** 지금 바로 쉬어야 할 때(지금 불편해요): 가장 가까운 곳. */
    fun nearest(ahead: List<StopEta>, preferServiceArea: Boolean): Choice? {
        val near = ahead.take(3)
        val pick = (if (preferServiceArea) near.firstOrNull { it.stop.kind == RestStop.Kind.SERVICE_AREA } else null) ?: ahead.firstOrNull()
        return pick?.let { Choice(it, isLastBeforeDeadline = false, late = false) }
    }
}
