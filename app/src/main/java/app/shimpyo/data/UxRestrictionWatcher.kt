package app.shimpyo.data

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AAOS 표준 CarUxRestrictionsManager로 주행 제한 상태를 받는다(Pleos 문서 '주행 규제 개발 가이드').
 * 서비스를 쓸 수 없으면 null을 유지하고, 화면은 기어 상태로 대신 판단한다.
 */
class UxRestrictionWatcher(private val context: Context) {
    private val _restricted = MutableStateFlow<Boolean?>(null)
    /** true = 주행 중 제한 화면(카드·큰 버튼만), false = 정차 화면, null = 알 수 없음. */
    val restricted: StateFlow<Boolean?> = _restricted.asStateFlow()
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        runCatching {
            val c = Car.createCar(context.applicationContext) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            _restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization
            m.registerListener { r: CarUxRestrictions -> _restricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { Log.w("UxRestriction", "unavailable", it) }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null; car = null
    }
}
