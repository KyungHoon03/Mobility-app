package app.shimpyo.data

import app.shimpyo.domain.CarLocation
import app.shimpyo.domain.GearState
import app.shimpyo.domain.GeoPoint
import app.shimpyo.domain.RestOpportunity
import app.shimpyo.domain.RestStop
import app.shimpyo.domain.RouteContext
import app.shimpyo.domain.SeatPos
import app.shimpyo.domain.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.sin

/**
 * 시연용 가상 경로(1분 단위). 안드로이드에 기대지 않아 단위 테스트에서도 그대로 돌린다.
 *
 * 오후 1시 출발 → 곧은 고속도로 → 약 60km 지점부터 굽은 길(회전 안내가 700m마다) →
 * 받아들인 쉼터에 도착하면 15분 쉬고 다시 출발 → 두 번째 쉼터에 서면 시연을 마친다.
 */
class DemoRoute(start: GeoPoint = GeoPoint(37.40, 127.10)) {
    var minute = 0; private set
    var km = 0.0; private set
    var point = start; private set
    var gear = GearState.PARK; private set
    var legsDone = 0; private set
    val finished: Boolean get() = legsDone >= 2

    private var parkedUntil: Int? = null
    private var arrivalAt: Int? = null
    private var arrivalStop: RestStop? = null

    val curvyNow: Boolean get() = km in CURVY_KM
    val speedKph: Double get() = if (gear != GearState.DRIVE) 0.0 else if (curvyNow) 70.0 else 95.0

    fun depart() { if (gear == GearState.PARK && !finished) gear = GearState.DRIVE }

    /** 쉼터를 경유지로 받으면 도착 예정 시각에 그곳에 선다. */
    fun waypoint(stop: RestStop) {
        val etaMin = RestOpportunity.stopsAhead(listOf(stop), location(), 95.0).firstOrNull()?.etaMin
            ?: (app.shimpyo.domain.Geo.distanceKm(point, stop.point) * RestOpportunity.ROAD_FACTOR / (95.0 / 60.0))
        arrivalAt = minute + ceil(etaMin).toInt().coerceAtLeast(1)
        arrivalStop = stop
    }

    fun location() = CarLocation(point, HEADING)

    fun turnsAheadM(): List<Double> =
        TURNS_KM.filter { it > km }.map { (it - km) * 1000.0 } + listOf((FAR_TURN_KM - km).coerceAtLeast(1.0) * 1000.0)

    fun advance() {
        minute += 1
        when (gear) {
            GearState.PARK -> parkedUntil?.let { if (minute >= it) { gear = GearState.DRIVE; parkedUntil = null } }
            else -> {
                val stop = arrivalStop
                if (arrivalAt != null && stop != null && minute >= arrivalAt!!) {
                    point = stop.point
                    gear = GearState.PARK
                    legsDone += 1
                    arrivalAt = null; arrivalStop = null
                    if (!finished) parkedUntil = minute + REST_MIN
                } else {
                    val kmThisMin = speedKph / 60.0
                    km += kmThisMin
                    point = point.copy(lat = point.lat - kmThisMin / 111.0)
                }
            }
        }
    }

    companion object {
        const val HEADING = 180.0
        const val REST_MIN = 15
        val CURVY_KM = 60.0..66.0
        val TURNS_KM: List<Double> = generateSequence(60.0) { it + 0.7 }.takeWhile { it <= 66.0 }.toList()
        const val FAR_TURN_KM = 250.0
    }
}

/**
 * 시연 모드. 에뮬레이터에서 차량 값을 직접 바꾸기 어려울 때를 위해 [DemoRoute]를 빠르게 재생한다(1초 = 주행 1분).
 * 실제 차량 데이터가 아님을 화면에 표시한다.
 */
class DemoDrive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    val routeModel = DemoRoute()

    private val vehicleState = MutableStateFlow(snapshot())
    private val routeState = MutableStateFlow(RouteContext())
    private val naviStatus = MutableStateFlow("시연 모드: 가상 경로")

    val vehicle: VehicleSource = object : VehicleSource {
        override val snapshot: StateFlow<VehicleSnapshot> = vehicleState.asStateFlow()
        override val status: StateFlow<String> = MutableStateFlow("시연 모드: 재생 중인 가상 주행").asStateFlow()
        override fun start() = startScript()
        override fun stop() = stopScript()
    }

    val navi: NaviGateway = object : NaviGateway {
        override val route: StateFlow<RouteContext> = routeState.asStateFlow()
        override val status: StateFlow<String> = naviStatus.asStateFlow()
        override fun start() {}
        override fun stop() {}
        override fun addWaypoint(stop: RestStop) {
            routeModel.waypoint(stop)
            naviStatus.value = "시연 모드: ${stop.name}을(를) 경유지로 넘겼다고 가정해요"
        }
    }

    private fun snapshot(steer: Float = 0f, brake: Boolean = false) = VehicleSnapshot(
        steeringAngleDeg = steer,
        brakePressed = brake,
        gear = routeModel.gear,
        occupiedSeats = setOf(SeatPos.DRIVER, SeatPos.REAR_LEFT),
        cabinTempC = 25.5f,
        recirculationOn = true,
        tripDrivingMinutes = null,
        speedKph = routeModel.speedKph.toFloat(),
    )

    private fun publish(steer: Float = 0f, brake: Boolean = false) {
        vehicleState.value = snapshot(steer, brake)
        routeState.value = RouteContext(routeModel.location(), routeModel.turnsAheadM())
    }

    private fun startScript() {
        if (job != null) return
        routeModel.depart()
        publish()
        job = scope.launch {
            while (isActive && !routeModel.finished) {
                routeModel.advance()
                publish(steer = (0.3 * sin(routeModel.minute * 0.2)).toFloat())
                // 굽은 길에서는 한 "분" 안에서 조향을 몇 번 흔들어 주행 변화가 잡히게 한다.
                if (routeModel.curvyNow && routeModel.gear == GearState.DRIVE) {
                    repeat(2) { i ->
                        delay(300)
                        publish(steer = if (i % 2 == 0) 5f else -5f)
                    }
                    delay(400)
                } else {
                    delay(1_000)
                }
            }
        }
    }

    private fun stopScript() {
        job?.cancel(); job = null
    }
}
