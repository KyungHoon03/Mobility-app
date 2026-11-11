package app.shimpyo.data

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.shimpyo.domain.CarLocation
import app.shimpyo.domain.GeoPoint
import app.shimpyo.domain.RestStop
import app.shimpyo.domain.RouteContext
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

/**
 * Pleos NaviHelper SDK 연동. 현재 위치·회전 안내(TBT)를 주기적으로 받아 오고, 쉼터를 경유지로 추가한다.
 * NaviHelper에는 휴게소 검색 API가 없으므로 쉼터 후보는 앱에 넣은 공개 데이터에서 고른다.
 */
class PleosNaviGateway(context: Context) : NaviGateway {

    private val helper = NaviHelper(context.applicationContext)
    private val _route = MutableStateFlow(RouteContext())
    override val route: StateFlow<RouteContext> = _route.asStateFlow()
    private val _status = MutableStateFlow("내비 연결 전")
    override val status: StateFlow<String> = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _route.update {
                it.copy(location = CarLocation(GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude), currentLocationInfo.heading))
            }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            _route.update { it.copy(upcomingTurnDistancesM = tbtInfoList.map { tbt -> tbt.distance }) }
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            _status.value = "경유지가 바뀌었어요"
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            _status.value = "내비 오류: $errorCode"
        }
    }

    override fun start() {
        if (connected) return
        _status.value = "내비 연결 중"
        helper.initialize(
            onConnected = {
                connected = true
                _status.value = "내비 연결됨"
                helper.addListener(listener)
                startPolling()
            },
            onFailed = { e ->
                Log.w(TAG, "navi connect failed", e)
                _status.value = "내비 연결 실패: ${e.message}"
            },
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching { helper.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "location", it) }
                runCatching { helper.getTBTInfo() }.onFailure { Log.w(TAG, "tbt", it) }
                delay(POLL_MS)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        if (connected) {
            runCatching { helper.removeListener(listener) }
            runCatching { helper.release() }
        }
        connected = false
    }

    override fun addWaypoint(stop: RestStop) {
        if (!connected) {
            _status.value = "내비가 연결되지 않아 경유지를 추가하지 못했어요"
            return
        }
        runCatching {
            helper.addWaypoint(
                RequestWaypointInfo(
                    latitude = stop.point.lat,
                    longitude = stop.point.lon,
                    poiName = stop.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = stop.name,
                    poiSubId = "0",
                ),
            )
        }.onSuccess {
            _status.value = "${stop.name}을(를) 경유지로 요청했어요"
        }.onFailure { e ->
            Log.w(TAG, "addWaypoint failed", e)
            _status.value = "경유지 추가 실패: ${e.message}"
        }
    }

    private companion object {
        const val TAG = "PleosNaviGateway"
        const val POLL_MS = 10_000L
    }
}
