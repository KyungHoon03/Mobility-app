package app.shimpyo.data

import app.shimpyo.domain.RestStop
import app.shimpyo.domain.RouteContext
import app.shimpyo.domain.VehicleSnapshot
import kotlinx.coroutines.flow.StateFlow

interface VehicleSource {
    val snapshot: StateFlow<VehicleSnapshot>
    /** 연결 상태 설명(정차 화면에 표시). */
    val status: StateFlow<String>
    fun start()
    fun stop()
}

interface NaviGateway {
    val route: StateFlow<RouteContext>
    val status: StateFlow<String>
    fun start()
    fun stop()
    /** 쉼터를 첫 번째 경유지로 추가한다. 결과는 [status]로 알린다. */
    fun addWaypoint(stop: RestStop)
}
