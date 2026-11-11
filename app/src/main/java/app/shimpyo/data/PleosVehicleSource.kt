package app.shimpyo.data

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.util.Log
import app.shimpyo.domain.GearState
import app.shimpyo.domain.SeatPos
import app.shimpyo.domain.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Vehicle SDK로 차량 상태를 읽는다. 읽기 API만 쓰고 차량 제어(set) API는 쓰지 않는다.
 * 각 구독이 실패해도 앱은 멈추지 않고, 해당 값만 비운 채 상태 문구에 남긴다.
 */
class PleosVehicleSource(context: Context) : VehicleSource {

    private val vehicle = Vehicle(context.applicationContext)
    private val _snapshot = MutableStateFlow(VehicleSnapshot(occupiedSeats = setOf(SeatPos.DRIVER)))
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()
    private val _status = MutableStateFlow("차량 서비스 연결 전")
    override val status: StateFlow<String> = _status.asStateFlow()
    private val failures = linkedSetOf<String>()
    private var started = false
    private var usingAaosGear = false
    private val gearWatcher = AaosGearWatcher(context) { g -> _snapshot.update { it.copy(gear = g) } }

    private val climateArea = SeatArea(SeatValue.ROW_1_LEFT)
    private val seatAreas = mapOf(
        SeatPos.FRONT_PASSENGER to SeatArea(SeatValue.ROW_1_RIGHT),
        SeatPos.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
        SeatPos.REAR_CENTER to SeatArea(SeatValue.ROW_2_CENTER),
        SeatPos.REAR_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT),
    )

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = _snapshot.update { it.copy(steeringAngleDeg = angle) }
        override fun onFailed(e: Exception) = fail("조향각", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = _snapshot.update { it.copy(brakePressed = status) }
        override fun onFailed(e: Exception) = fail("브레이크", e)
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = _snapshot.update { it.copy(gear = gear.toGearState()) }
        override fun onFailed(e: Exception) = fail("기어", e)
    }
    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) = _snapshot.update { it.copy(tripDrivingMinutes = value) }
        override fun onFailed(e: Exception) = fail("주행 시간", e)
    }
    private val temperatureListener = object : HvacTemperatureValueListener {
        override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) =
            _snapshot.update { it.copy(cabinTempC = celsius) }
        override fun onFailed(e: Exception) = fail("실내 온도", e)
    }
    private val recirculationListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) =
            _snapshot.update { it.copy(recirculationOn = state) }
        override fun onFailed(e: Exception) = fail("내기순환", e)
    }
    private val seatListeners = seatAreas.mapValues { (pos, _) ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = _snapshot.update {
                val seats = it.occupiedSeats.toMutableSet()
                if (occupancy == true) seats += pos else seats -= pos
                it.copy(occupiedSeats = seats)
            }
            override fun onFailed(e: Exception) = fail("좌석(${pos.name})", e)
        }
    }

    override fun start() {
        if (started) return
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            _status.value = "차량 서비스 연결 실패: ${e.message}"
            Log.w(TAG, "initialize failed", e)
            return
        }
        started = true
        safely("조향각") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        safely("브레이크") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        usingAaosGear = gearWatcher.start()
        @Suppress("DEPRECATION")
        if (!usingAaosGear) {
            // 표준 속성을 쓸 수 없을 때만 Pleos 기어 API로 대신한다.
            safely("기어") { vehicle.getGear().registerGearSelection(gearListener) }
            safely("기어") {
                vehicle.getGear().getCurrentGearSelectionState({ g -> _snapshot.update { it.copy(gear = g.toGearState()) } }, { fail("기어", it) })
            }
        }
        safely("주행 시간") { vehicle.getOdometer().registerTripCurrentDrivingTime(drivingTimeListener) }
        safely("실내 온도") { vehicle.getHVAC().registerHvacTemperatureCurrent(climateArea, temperatureListener) }
        safely("내기순환") { vehicle.getHVAC().registerHvacRecirculationState(climateArea, recirculationListener) }
        seatAreas.forEach { (pos, area) ->
            safely("좌석(${pos.name})") { vehicle.getSeat().registerSeatOccupancy(area, seatListeners.getValue(pos)) }
        }
        refreshStatus()
    }

    override fun stop() {
        if (!started) return
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        gearWatcher.stop()
        @Suppress("DEPRECATION")
        if (!usingAaosGear) runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getOdometer().unregisterTripCurrentDrivingTime(drivingTimeListener) }
        runCatching { vehicle.getHVAC().unregisterHvacTemperatureCurrent(climateArea, temperatureListener) }
        runCatching { vehicle.getHVAC().unregisterHvacRecirculationState(climateArea, recirculationListener) }
        seatAreas.forEach { (pos, area) -> runCatching { vehicle.getSeat().unregisterSeatOccupancy(area, seatListeners.getValue(pos)) } }
        runCatching { vehicle.release() }
        started = false
    }

    private fun safely(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(name, e) }
    }

    private fun fail(name: String, e: Exception) {
        Log.w(TAG, "$name failed", e)
        failures += name
        refreshStatus()
    }

    private fun refreshStatus() {
        _status.value = if (failures.isEmpty()) "차량 서비스 연결됨" else "차량 서비스 연결됨 · 읽지 못한 값: ${failures.joinToString()}"
    }

    private fun VehicleGear?.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE // D 및 수동 단수
    }

    private companion object { const val TAG = "PleosVehicleSource" }
}
