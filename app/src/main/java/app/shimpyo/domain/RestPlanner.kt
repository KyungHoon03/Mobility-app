package app.shimpyo.domain

import java.util.Calendar

data class Suggestion(
    val condition: Condition,
    val profileId: String,
    val stop: RestStopSelector.Pick?,
    val actions: List<String>,
    val score: Double,
    val threshold: Double,
)

/**
 * 차량·내비 데이터를 받아 점수를 계산하고, 사람별 기준점을 넘으면 쉼터를 제안한다.
 *
 * - 주행 중에만 제안하고, 같은 상태는 [cooldownMs] 동안 다시 제안하지 않는다.
 * - 졸음 응답은 주행 중에 묻지 않고, [askAfterDriveMin]분 이상 운전한 뒤 기어가 P로 바뀌었을 때만 묻는다.
 */
class RestPlanner(
    private val personalizer: Personalizer = Personalizer(),
    private val cooldownMs: Long = 20 * 60_000L,
    private val askAfterDriveMin: Double = 15.0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hourOf: (Long) -> Int = { ms -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) },
) {
    val changeIndex = DrivingChangeIndex()

    private var tripStartMs: Long? = null
    private var recirculationSinceMs: Long? = null
    private var lastGear = GearState.UNKNOWN
    private val lastSuggestedAt = mutableMapOf<Condition, Long>()

    var pendingDrowsinessQuestion: Boolean = false
        private set

    /** 마지막으로 계산한 특징값과 점수(화면 표시·기록용). */
    var lastFeatures: Features? = null
        private set

    fun onVehicle(s: VehicleSnapshot) {
        val now = clock()
        s.steeringAngleDeg?.let { changeIndex.onSteering(now, it) }
        s.brakePressed?.let { changeIndex.onBrake(now, it) }

        if (s.recirculationOn == true) {
            if (recirculationSinceMs == null) recirculationSinceMs = now
        } else if (s.recirculationOn == false) {
            recirculationSinceMs = null
        }

        if (s.gear != lastGear) {
            val driving = s.gear == GearState.DRIVE || s.gear == GearState.REVERSE
            if (driving && tripStartMs == null) {
                tripStartMs = now
                changeIndex.reset()
            }
            if (s.gear == GearState.PARK) {
                val minutes = minutesSinceStart(now, s)
                if (minutes >= askAfterDriveMin) pendingDrowsinessQuestion = true
                tripStartMs = null
            }
            lastGear = s.gear
        }
    }

    fun answeredDrowsinessQuestion() {
        pendingDrowsinessQuestion = false
    }

    fun features(s: VehicleSnapshot, route: RouteContext): Features {
        val now = clock()
        return Features(
            drivingChange = changeIndex.value(now),
            curveAhead = RiskModel.curveAhead(route.upcomingTurnDistancesM),
            minutesSinceStart = minutesSinceStart(now, s),
            hourOfDay = hourOf(now),
            cabinTempC = s.cabinTempC?.toDouble(),
            recirculationMinutes = recirculationSinceMs?.let { (now - it) / 60_000.0 } ?: 0.0,
        ).also { lastFeatures = it }
    }

    /**
     * 지금 제안할 것이 있으면 하나를 돌려준다. 졸음(운전자 안전)을 멀미보다 먼저 본다.
     * @param passengerProfileId 멀미 기록을 연결할 동승자 프로필. 동승자가 없으면 null.
     */
    fun evaluate(
        s: VehicleSnapshot,
        route: RouteContext,
        reports: List<Report>,
        driverProfileId: String,
        passengerProfileId: String?,
        stops: List<RestStop>,
        caffeineOk: Boolean,
    ): Suggestion? {
        if (s.gear != GearState.DRIVE) return null
        val f = features(s, route)
        val now = clock()
        val candidates = buildList {
            add(Condition.DROWSINESS to driverProfileId)
            if (passengerProfileId != null) add(Condition.MOTION_SICKNESS to passengerProfileId)
        }
        for ((condition, profileId) in candidates) {
            val last = lastSuggestedAt[condition]
            if (last != null && now - last < cooldownMs) continue
            val score = RiskModel.score(condition, f)
            val threshold = personalizer.threshold(reports, condition, profileId)
            if (score >= threshold) {
                lastSuggestedAt[condition] = now
                val pick = route.location?.let { RestStopSelector.nextAhead(stops, it) }
                return Suggestion(condition, profileId, pick, Advice.forCondition(condition, caffeineOk), score, threshold)
            }
        }
        return null
    }

    fun minutesSinceStart(now: Long, s: VehicleSnapshot): Double {
        s.tripDrivingMinutes?.let { if (it > 0) return it.toDouble() }
        return tripStartMs?.let { (now - it) / 60_000.0 } ?: 0.0
    }
}
