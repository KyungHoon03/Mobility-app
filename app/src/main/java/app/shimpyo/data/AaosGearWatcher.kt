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
 * 기어 상태를 AAOS 표준 속성(VehiclePropertyIds.GEAR_SELECTION)으로 읽는다.
 * Pleos Vehicle SDK의 기어 API는 "Google GEAR_SELECTION 속성을 쓰라"며 사용 중단 예정이라 표준 방식을 우선한다.
 */
@Suppress("DEPRECATION") // 대상 기기가 API 34라 API 35의 subscribePropertyEvents 대신 registerCallback을 쓴다
class AaosGearWatcher(private val context: Context, private val onGear: (GearState) -> Unit) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Int)?.let { onGear(it.toGearState()) }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "gear error $propertyId")
        }
    }

    /** @return 표준 속성 구독에 성공하면 true. */
    fun start(): Boolean = runCatching {
        val c = Car.createCar(context.applicationContext) ?: return false
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return false
        manager = m
        val ok = m.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        if (ok) runCatching { (m.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).value as? Int)?.let { onGear(it.toGearState()) } }
        ok
    }.getOrElse {
        Log.w(TAG, "GEAR_SELECTION unavailable", it)
        false
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

    private companion object { const val TAG = "AaosGearWatcher" }
}
