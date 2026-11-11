package app.shimpyo.domain

import kotlin.math.max

enum class CareStage {
    /** 모두 여유가 넉넉함: 상태 한 줄만. */
    WATCH,
    /** 가장 짧은 여유가 30분 안: 버튼 없는 한 줄 알림. */
    HEADS_UP,
    /** 쉴 수 있는 기회가 15분 안에 닿음: 챙김 카드 1장과 버튼 1개. */
    CARE,
}

data class PersonState(
    val passenger: Passenger,
    /** 지금 통이 찬 정도. */
    val load: Double,
    /** 학습된 통 크기(가운데 값). */
    val capacity: Double,
    val margin: Forecaster.Margin,
)

data class CarePlan(
    val stage: CareStage = CareStage.WATCH,
    val people: List<PersonState> = emptyList(),
    /** 가장 먼저 힘들어질 사람(계획의 기준). */
    val focus: PersonState? = null,
    val choice: RestOpportunity.Choice? = null,
    val minutesToCurvy: Double? = null,
    /** 동승자가 "지금 불편해요"를 눌러 바로 쉬어야 하는 계획. */
    val urgent: Boolean = false,
    /** 함께 쉬면 좋은 둘째 사람. */
    val companion: PersonState? = null,
    val continuousDrivingMin: Double = 0.0,
    val hour: Int = 0,
)

/** 쉼터에 주차했을 때 묻는 대상과 그때의 통 채움 정도. */
data class ArrivalQuestion(val person: Passenger, val load: Double, val viaPlan: Boolean)

/**
 * 사람 → 판단 → 케어 → 내비 중 "판단"을 맡는다.
 * 차량 값을 받아 사람별 통을 채우고(쉬면 비우고), 앞으로의 여유와 휴식 기회를 계산한다.
 * 화면 문구와 내비 연동은 바깥에서 한다. 순수 Kotlin이라 단위 테스트로 검증한다.
 */
class CarePlanner(
    private val clock: () -> Long,
    private val hourMinuteOf: (Long) -> Pair<Int, Int>,
) {
    val changeIndex = DrivingChangeIndex()

    private val loads = mutableMapOf<String, Double>()
    private var lastTickMs: Long? = null
    private var continuousDrivingMin = 0.0
    private var parkedMin = 0.0
    private var recirculationSinceMs: Long? = null
    private var lastGear = GearState.UNKNOWN
    private var drivenThisLegMin = 0.0

    /** 운전자가 받아들인 계획. 그 쉼터에 서면 그 사람에게 묻는다. */
    var accepted: CarePlan? = null
        private set
    private var dismissedStop: String? = null
    /** 챙김 카드를 띄운 뒤에는 고른 쉼터를 고정한다(카드가 보이는 동안 장소가 바뀌지 않게). */
    private var locked: RestOpportunity.Choice? = null
    private var urgentKey: String? = null
    private var urgentUntilMs = 0L

    var arrivalQuestion: ArrivalQuestion? = null
        private set

    fun loadOf(p: Passenger): Double = loads[p.key] ?: 0.0

    fun onVehicle(s: VehicleSnapshot) {
        val now = clock()
        s.steeringAngleDeg?.let { changeIndex.onSteering(now, it) }
        s.brakePressed?.let { changeIndex.onBrake(now, it) }
        if (s.recirculationOn == true) {
            if (recirculationSinceMs == null) recirculationSinceMs = now
        } else if (s.recirculationOn == false) {
            recirculationSinceMs = null
        }
    }

    /**
     * 1분 안팎마다 부른다. 지난 호출 뒤 흐른 시간만큼 통을 채우거나 비우고, 새 계획을 낸다.
     */
    fun tick(
        s: VehicleSnapshot,
        route: RouteContext,
        passengers: List<Passenger>,
        calibrations: Map<String, Calibration>,
        stops: List<RestStop>,
    ): CarePlan {
        val now = clock()
        val dtMin = lastTickMs?.let { ((now - it) / 60_000.0).coerceIn(0.0, 5.0) } ?: 0.0
        lastTickMs = now
        val driving = s.gear == GearState.DRIVE || s.gear == GearState.REVERSE
        val (hour, minute) = hourMinuteOf(now)
        val speed = (s.speedKph?.toDouble() ?: DEFAULT_SPEED_KPH)
        val change = changeIndex.value(now)
        val recircMin = recirculationSinceMs?.let { (now - it) / 60_000.0 } ?: 0.0

        handleGear(s.gear, passengers)

        val ctx = DriveContext(hour, minute, continuousDrivingMin, change, s.cabinTempC?.toDouble(), recircMin, speed, route.upcomingTurnDistancesM)
        val route0 = RouteAhead(route.upcomingTurnDistancesM, speed)
        passengers.forEach { p ->
            val l = loads[p.key] ?: 0.0
            loads[p.key] = if (driving) l + Forecaster.rateAt(p, 0.0, ctx, route0) * dtMin
            else max(0.0, l - ComfortRates.RECOVERY_PER_MIN * dtMin)
        }
        if (driving) {
            continuousDrivingMin += dtMin
            drivenThisLegMin += dtMin
            parkedMin = 0.0
        } else {
            parkedMin += dtMin
            if (parkedMin >= REST_RESETS_DRIVING_MIN) continuousDrivingMin = 0.0
        }

        val people = passengers.map { p ->
            val cap = (calibrations[p.key] ?: Calibration()).estimate
            PersonState(p, loads[p.key] ?: 0.0, cap, Forecaster.margin(p, loads[p.key] ?: 0.0, cap, ctx))
        }
        val minutesToCurvy = route0.minutesToNextCurvy()
        if (!driving || people.isEmpty()) return CarePlan(people = people, minutesToCurvy = minutesToCurvy, continuousDrivingMin = continuousDrivingMin, hour = hour)

        val ahead = route.location?.let { RestOpportunity.stopsAhead(stops, it, speed) } ?: emptyList()

        // 동승자가 "지금 불편해요"를 누른 뒤에는 그 사람을 기준으로 가장 가까운 곳을 고른다.
        if (urgentKey != null && now < urgentUntilMs && accepted == null) {
            val focus = people.firstOrNull { it.passenger.key == urgentKey }
            if (focus != null) {
                return CarePlan(
                    CareStage.CARE, people, focus, RestOpportunity.nearest(ahead, focus.passenger.role == Role.CHILD),
                    minutesToCurvy, urgent = true, continuousDrivingMin = continuousDrivingMin, hour = hour,
                )
            }
        }

        val focus = people.minByOrNull { it.margin.point ?: Double.MAX_VALUE }!!
        val deadline = focus.margin.point
        val prefer = focus.passenger.role == Role.CHILD
        val fresh = deadline?.let {
            if (focus.passenger.condition == Condition.MOTION_SICKNESS) RestOpportunity.chooseForMotion(ahead, it, minutesToCurvy, prefer)
            else RestOpportunity.choose(ahead, it, prefer)
        }
        // 고정한 쉼터는 가까워지며 옆으로 비껴 보여도 지나치기 전까지 유지한다(뒤쪽 100° 밖이거나 0.5km 안이면 지난 것으로 본다).
        val lockedNow = locked?.let { l -> route.location?.let { loc -> RestOpportunity.track(l.stop.stop, loc, speed)?.let { l.copy(stop = it) } } }
        if (locked != null && lockedNow == null) locked = null
        val choice = lockedNow ?: fresh
        val companion = people.filter { it !== focus }
            .firstOrNull { other -> other.margin.point != null && choice != null && other.margin.point <= choice.stop.etaMin + COMPANION_WINDOW_MIN }

        val stage = when {
            accepted != null -> CareStage.WATCH
            deadline == null -> CareStage.WATCH
            choice != null && choice.stop.stop.name != dismissedStop &&
                (choice.stop.etaMin <= CARE_ETA_MIN || choice.late) -> CareStage.CARE
            deadline <= HEADS_UP_MIN -> CareStage.HEADS_UP
            else -> CareStage.WATCH
        }
        if (stage == CareStage.CARE && locked == null) locked = choice
        return CarePlan(stage, people, focus, choice, minutesToCurvy, companion = companion, continuousDrivingMin = continuousDrivingMin, hour = hour)
    }

    private fun handleGear(gear: GearState, passengers: List<Passenger>) {
        if (gear == lastGear) return
        val driving = gear == GearState.DRIVE || gear == GearState.REVERSE
        if (driving && (lastGear == GearState.PARK || lastGear == GearState.UNKNOWN)) {
            drivenThisLegMin = 0.0
            changeIndex.reset()
        }
        if (gear == GearState.PARK && drivenThisLegMin >= ASK_AFTER_DRIVE_MIN) {
            val plan = accepted
            val target = plan?.focus?.passenger ?: passengers.firstOrNull { it.role == Role.DRIVER }
            if (target != null) arrivalQuestion = ArrivalQuestion(target, loads[target.key] ?: 0.0, viaPlan = plan != null)
            accepted = null
            dismissedStop = null
            locked = null
        }
        lastGear = gear
    }

    fun accept(plan: CarePlan) {
        accepted = plan
        locked = null
        urgentKey = null
    }

    fun dismiss(plan: CarePlan) {
        dismissedStop = plan.choice?.stop?.stop?.name
        urgentKey = null
        locked = null
    }

    /** 동승자가 누른 "지금 불편해요". 그 순간의 통 채움 정도를 돌려준다(학습용). */
    fun reportNow(p: Passenger): Double {
        urgentKey = p.key
        urgentUntilMs = clock() + URGENT_HOLD_MS
        accepted = null
        dismissedStop = null
        return loads[p.key] ?: 0.0
    }

    fun answered() {
        arrivalQuestion = null
    }

    fun reset() {
        loads.clear(); lastTickMs = null; continuousDrivingMin = 0.0; parkedMin = 0.0
        recirculationSinceMs = null; lastGear = GearState.UNKNOWN; drivenThisLegMin = 0.0
        accepted = null; dismissedStop = null; urgentKey = null; arrivalQuestion = null; locked = null
        changeIndex.reset()
    }

    companion object {
        const val HEADS_UP_MIN = 30.0
        const val CARE_ETA_MIN = 15.0
        const val COMPANION_WINDOW_MIN = 20.0
        const val ASK_AFTER_DRIVE_MIN = 10.0
        const val REST_RESETS_DRIVING_MIN = 10.0
        const val DEFAULT_SPEED_KPH = 80.0
        const val URGENT_HOLD_MS = 20 * 60_000L
    }
}
