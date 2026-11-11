package app.shimpyo.data

import app.shimpyo.domain.CarLocation
import app.shimpyo.domain.GearState
import app.shimpyo.domain.GeoPoint
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * 시연 모드. 에뮬레이터에서 차량 값을 직접 바꾸기 어려울 때를 위해,
 * 굽은 길을 달리는 주행을 빠르게 재생한다(1초 = 주행 1분). 실제 차량 데이터가 아님을 화면에 표시한다.
 */
class DemoDrive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val vehicleState = MutableStateFlow(VehicleSnapshot(gear = GearState.PARK, occupiedSeats = setOf(SeatPos.DRIVER, SeatPos.REAR_LEFT)))
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
            naviStatus.value = "시연 모드: ${stop.name}을(를) 경유지로 추가했다고 가정해요"
        }
    }

    private fun startScript() {
        if (job != null) return
        job = scope.launch {
            var minute = 0
            var lat = 37.30
            val lon = 127.05
            vehicleState.update { it.copy(gear = GearState.DRIVE, cabinTempC = 25.5f, recirculationOn = true) }
            while (isActive) {
                minute += 1
                lat -= 0.004 // 남쪽으로 이동
                val curvy = minute in 25..45
                vehicleState.update {
                    it.copy(
                        steeringAngleDeg = if (curvy) (6 * sin(minute * 1.7)).toFloat() else (0.3 * sin(minute * 0.2)).toFloat(),
                        brakePressed = curvy && minute % 2 == 0,
                        tripDrivingMinutes = minute,
                    )
                }
                routeState.value = RouteContext(
                    location = CarLocation(GeoPoint(lat, lon), headingDeg = 180.0),
                    upcomingTurnDistancesM = if (curvy) listOf(400.0, 900.0, 1500.0, 2200.0, 2900.0) else listOf(12_000.0),
                )
                // 조향 방향 전환이 여러 번 잡히도록 한 "분" 안에서 값을 몇 번 흔든다.
                if (curvy) {
                    repeat(3) { i ->
                        delay(200)
                        vehicleState.update { s -> s.copy(steeringAngleDeg = if (i % 2 == 0) 5f else -5f, brakePressed = i == 1) }
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
        vehicleState.update { it.copy(gear = GearState.PARK) }
    }
}
