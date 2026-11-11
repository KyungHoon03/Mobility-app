package app.shimpyo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shimpyo.data.DemoDrive
import app.shimpyo.data.NaviGateway
import app.shimpyo.data.PleosNaviGateway
import app.shimpyo.data.PleosVehicleSource
import app.shimpyo.data.RecordStore
import app.shimpyo.data.Records
import app.shimpyo.data.RestStopRepository
import app.shimpyo.data.Settings
import app.shimpyo.data.UxRestrictionWatcher
import app.shimpyo.data.VehicleSource
import app.shimpyo.domain.ArrivalQuestion
import app.shimpyo.domain.Calibration
import app.shimpyo.domain.CareEvent
import app.shimpyo.domain.CarePlan
import app.shimpyo.domain.CarePlanner
import app.shimpyo.domain.CareStage
import app.shimpyo.domain.Condition
import app.shimpyo.domain.GearState
import app.shimpyo.domain.Passenger
import app.shimpyo.domain.RestFeedback
import app.shimpyo.domain.Role
import app.shimpyo.domain.RouteContext
import app.shimpyo.domain.SeatPos
import app.shimpyo.domain.VehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 쉼터에 도착해 정차 화면에서 보여 줄 케어(받아들인 계획의 기준이 된 사람). */
data class RestCare(val person: Passenger, val place: String)

data class UiState(
    val driving: Boolean = false,
    val demo: Boolean = false,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val route: RouteContext = RouteContext(),
    val plan: CarePlan = CarePlan(),
    /** 운전자가 받아들여 내비로 넘긴 계획(쉼터로 가는 중). */
    val heading: CarePlan? = null,
    val passengers: List<Passenger> = emptyList(),
    val choosingWho: Boolean = false,
    val arrival: ArrivalQuestion? = null,
    val restCare: RestCare? = null,
    val settings: Settings = Settings(),
    val calibrations: Map<String, Calibration> = emptyMap(),
    val events: List<CareEvent> = emptyList(),
    val vehicleStatus: String = "",
    val naviStatus: String = "",
    val message: String? = null,
    val restStopCount: Int = 0,
    val napSecondsLeft: Long? = null,
    /** 시연 모드의 가상 시각(예: 오후 1:25). */
    val simulatedClock: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RecordStore(app)
    private val restStops = RestStopRepository(app)
    private val planner = CarePlanner(clock = { now() }, hourMinuteOf = { ms -> hourMinute(ms) })
    private val ux = UxRestrictionWatcher(app)
    private val realVehicle: VehicleSource = PleosVehicleSource(app)
    private val realNavi: NaviGateway = PleosNaviGateway(app)
    private var demo: DemoDrive? = null

    // 시연 모드는 실제 1초를 주행 1분으로 압축한다. 판단 로직도 같은 시계를 쓴다.
    private var demoStartReal = 0L
    private var demoBase = 0L
    private fun now(): Long =
        if (demo != null) demoBase + (System.currentTimeMillis() - demoStartReal) * DEMO_SPEED else System.currentTimeMillis()
    private fun hourMinute(ms: Long): Pair<Int, Int> =
        Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) }

    private var napEndsAtReal: Long? = null
    private val tone by lazy { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90) }.getOrNull() }

    /** 실제 기록. 시연 모드에서는 [demoRecords]를 따로 써서 실제 기록을 건드리지 않는다. */
    private var records = Records()
    private var demoRecords: Records? = null
    private val active: Records get() = demoRecords ?: records

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val vehicle: VehicleSource get() = demo?.vehicle ?: realVehicle
    private val navi: NaviGateway get() = demo?.navi ?: realNavi

    init {
        viewModelScope.launch {
            records = withContext(Dispatchers.IO) { store.load() }
            tick()
        }
        ux.start(); realVehicle.start(); realNavi.start()
        viewModelScope.launch {
            var i = 0
            while (isActive) {
                // 조향·브레이크 변화를 놓치지 않도록 100ms마다 읽고, 판단은 1초마다 한다.
                planner.onVehicle(vehicle.snapshot.value)
                if (i++ % 10 == 0) tick()
                delay(100)
            }
        }
    }

    /** 좌석 착석 정보와 출발 전 고른 사람을 합쳐 지금 차에 탄 사람을 만든다. */
    private fun passengers(s: VehicleSnapshot): List<Passenger> = buildList {
        add(Passenger(DRIVER, Role.DRIVER, SeatPos.DRIVER))
        active.settings.seats.forEach { (seat, role) ->
            if (seat in s.occupiedSeats || demo != null) add(Passenger(profileOf(role), role, seat))
        }
    }

    private fun profileOf(role: Role) = when (role) { Role.DRIVER -> DRIVER; Role.CHILD -> "child"; Role.ADULT -> "adult" }

    private fun tick() {
        val snapshot = vehicle.snapshot.value
        val route = navi.route.value
        val people = passengers(snapshot)
        val plan = planner.tick(snapshot, route, people, active.calibrations, restStops.stops)
        val gearDriving = snapshot.gear == GearState.DRIVE || snapshot.gear == GearState.REVERSE
        // 실제 차량에서는 플랫폼의 주행 제한 상태를 따르고, 시연 모드에서는 가상 기어를 따른다.
        val driving = if (demo != null) gearDriving else ux.restricted.value ?: gearDriving

        val napLeft = napEndsAtReal?.let { it - System.currentTimeMillis() }
        if (napLeft != null && napLeft <= 0) {
            napEndsAtReal = null
            runCatching { tone?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3_000) }
            showMessage("15분이 지났어요. 천천히 몸을 풀고 출발하세요")
        }

        val arrival = planner.arrivalQuestion
        _ui.update {
            it.copy(
                driving = driving,
                demo = demo != null,
                vehicle = snapshot,
                route = route,
                plan = plan,
                heading = planner.accepted,
                passengers = people,
                choosingWho = it.choosingWho && driving,
                arrival = arrival,
                restCare = if (arrival != null && it.restCare == null && arrival.viaPlan) RestCare(arrival.person, it.heading?.choice?.stop?.stop?.name ?: "쉼터")
                    else if (driving) null else it.restCare,
                settings = active.settings,
                calibrations = active.calibrations,
                events = active.events,
                napSecondsLeft = napEndsAtReal?.let { end -> ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0) },
                simulatedClock = if (demo != null) clockText(now()) else null,
                vehicleStatus = vehicle.status.value,
                naviStatus = navi.status.value,
                restStopCount = restStops.stops.size,
            )
        }
    }

    // ---- 주행 중 ----

    /** 운전자가 챙김 카드를 받아들임: 쉼터를 내비 경유지로 넘긴다. */
    fun acceptPlan() {
        val plan = _ui.value.plan
        if (plan.stage != CareStage.CARE) return
        val stop = plan.choice?.stop?.stop ?: return showMessage("가까운 쉼터를 찾지 못했어요. 안전한 곳에 잠시 멈춰 주세요")
        navi.addWaypoint(stop)
        planner.accept(plan)
        tick()
    }

    fun dismissPlan() {
        planner.dismiss(_ui.value.plan)
        tick()
    }

    /** 동승자가 누르는 "지금 불편해요". 누가 불편한지 고르게 한다. */
    fun openWho() = _ui.update { it.copy(choosingWho = true) }
    fun closeWho() = _ui.update { it.copy(choosingWho = false) }

    fun reportNow(p: Passenger) {
        val load = planner.reportNow(p)
        updateCalibration(p.key) { it.onNowUncomfortable(load) }
        addEvent(CareEvent(now(), p.key, "NOW_UNCOMFORTABLE", load))
        _ui.update { it.copy(choosingWho = false) }
        tick()
    }

    // ---- 쉼터에서 ----

    fun answerArrival(feedback: RestFeedback) {
        val q = planner.arrivalQuestion ?: return
        updateCalibration(q.person.key) { it.onRestFeedback(q.load, feedback) }
        addEvent(CareEvent(now(), q.person.key, feedback.name, q.load))
        planner.answered()
        showMessage(
            when (feedback) {
                RestFeedback.JUST_RIGHT -> "기억해 둘게요. 다음에도 이 리듬으로 챙길게요"
                RestFeedback.ALREADY_UNCOMFORTABLE -> "다음에는 조금 더 일찍 챙길게요"
                RestFeedback.COULD_GO_ON -> "다음에는 조금 더 가서 쉬어도 괜찮게 챙길게요"
            },
        )
        tick()
    }

    fun startNap() { napEndsAtReal = System.currentTimeMillis() + NAP_MS; tick() }
    fun cancelNap() { napEndsAtReal = null; tick() }
    fun clearRestCare() = _ui.update { it.copy(restCare = null) }

    // ---- 설정 ----

    fun setSeat(seat: SeatPos, role: Role?) = updateRecords { r ->
        val seats = r.settings.seats.toMutableMap()
        if (role == null) seats.remove(seat) else seats[seat] = role
        r.copy(settings = r.settings.copy(seats = seats))
    }

    fun setCaffeineOk(ok: Boolean) = updateRecords { it.copy(settings = it.settings.copy(caffeineOk = ok)) }

    fun toggleDemo() {
        val d = demo
        planner.reset()
        napEndsAtReal = null
        _ui.update { it.copy(restCare = null, choosingWho = false) }
        if (d == null) {
            // 시연 시나리오: 오후 1시 출발, 뒷좌석에 아이. 지난 여행에서 쌓인 기록이 있다고 가정한다(시연용, 실제 기록과 분리).
            demoBase = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 13); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            demoStartReal = System.currentTimeMillis()
            demoRecords = Records(
                settings = Settings(seats = mapOf(SeatPos.REAR_LEFT to Role.CHILD), caffeineOk = records.settings.caffeineOk),
                calibrations = mapOf(
                    "child|${Condition.MOTION_SICKNESS.name}" to Calibration(0.5, 0.7, 2),
                    "$DRIVER|${Condition.DROWSINESS.name}" to Calibration(0.5, 0.8, 1),
                ),
            )
            demo = DemoDrive().also { it.vehicle.start() }
        } else {
            d.vehicle.stop(); demo = null; demoRecords = null
        }
        tick()
    }

    private fun clockText(ms: Long): String {
        val (h, m) = hourMinute(ms)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$ampm $h12:${"%02d".format(m)}"
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun showMessage(m: String) = _ui.update { it.copy(message = m) }

    private fun updateCalibration(key: String, f: (Calibration) -> Calibration) = updateRecords { r ->
        r.copy(calibrations = r.calibrations + (key to f(r.calibrations[key] ?: Calibration())))
    }

    private fun addEvent(e: CareEvent) = updateRecords { it.copy(events = it.events + e) }

    private fun updateRecords(f: (Records) -> Records) {
        val d = demoRecords
        if (d != null) { demoRecords = f(d); tick(); return }
        records = f(records)
        val snapshot = records
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
        tick()
    }

    override fun onCleared() {
        demo?.vehicle?.stop()
        realVehicle.stop(); realNavi.stop(); ux.stop()
    }

    companion object {
        const val DRIVER = "driver"
        private const val DEMO_SPEED = 60L
        private const val NAP_MS = 15 * 60_000L
    }
}
