package app.shimpyo.data

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import app.shimpyo.domain.GearState

/**
 * AAOS 표준 속성으로 기어(GEAR_SELECTION)와 차 속도(PERF_VEHICLE_SPEED, m/s)를 읽는다.
 * Pleos Vehicle SDK의 기어 API는 "Google GEAR_SELECTION 속성을 쓰라"며 사용 중단 예정이라 표준 방식을 우선한다.
 * 차 속도는 앞으로의 굽은 길까지 몇 분인지, 쉼터까지 몇 분인지 계산하는 데 쓴다.
 */
@Suppress("DEPRECATION") // 대상 기기가 API 34라 API 35의 subscribePropertyEvents 대신 registerCallback을 쓴다
class AaosPropertyWatcher(
    private val context: Context,
    private val onGear: (GearState) -> Unit,
    private val onSpeedKph: (Float) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    var gearOk = false
        private set
    var speedOk = false
        private set

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { onGear(it.toGearState()) }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { onSpeedKph(it * 3.6f) }
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "property error $propertyId")
        }
    }

    fun start() {
        runCatching {
            val c = Car.createCar(context.applicationContext) ?: return
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
            manager = m
            gearOk = runCatching { m.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }.getOrDefault(false)
            if (gearOk) runCatching { (m.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).value as? Int)?.let { onGear(it.toGearState()) } }
            speedOk = runCatching { m.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f) }.getOrDefault(false)
        }.onFailure { Log.w(TAG, "car properties unavailable", it) }
    }

    fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null; car = null
    }

    private fun Int.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE // D 및 수동 단수
    }

    private companion object { const val TAG = "AaosPropertyWatcher" }
}
