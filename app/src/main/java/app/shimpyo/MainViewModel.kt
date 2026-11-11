package app.shimpyo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shimpyo.data.DemoDrive
import app.shimpyo.data.NaviGateway
import app.shimpyo.data.PleosNaviGateway
import app.shimpyo.data.PleosVehicleSource
import app.shimpyo.data.RecordStore
import app.shimpyo.data.RestStopRepository
import app.shimpyo.data.Settings
import app.shimpyo.data.UxRestrictionWatcher
import app.shimpyo.data.VehicleSource
import app.shimpyo.domain.Advice
import app.shimpyo.domain.Condition
import app.shimpyo.domain.Features
import app.shimpyo.domain.GearState
import app.shimpyo.domain.Personalizer
import app.shimpyo.domain.Report
import app.shimpyo.domain.RestPlanner
import app.shimpyo.domain.RestStopSelector
import app.shimpyo.domain.RiskModel
import app.shimpyo.domain.RouteContext
import app.shimpyo.domain.Suggestion
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

data class PersonalInfo(val profileName: String, val condition: Condition, val count: Int, val threshold: Double, val personalized: Boolean)

data class UiState(
    val driving: Boolean = false,
    val demo: Boolean = false,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val route: RouteContext = RouteContext(),
    val features: Features? = null,
    val motionScore: Double = 0.0,
    val drowsyScore: Double = 0.0,
    val suggestion: Suggestion? = null,
    val askDrowsiness: Boolean = false,
    val settings: Settings = Settings(),
    val personal: List<PersonalInfo> = emptyList(),
    val vehicleStatus: String = "",
    val naviStatus: String = "",
    val message: String? = null,
    val restStopCount: Int = 0,
    /** 마지막으로 제안한 쉼터와 할 일. 쉼터에 도착해 주차한 뒤 정차 화면에서 다시 보여 준다. */
    val restAdvice: Suggestion? = null,
    val napSecondsLeft: Long? = null,
    /** 시연 모드의 가상 시각(예: 오후 1:25). */
    val simulatedClock: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RecordStore(app)
    private val restStops = RestStopRepository(app)
    private val personalizer = Personalizer()
    private val planner = RestPlanner(personalizer, clock = { now() }, hourOf = { ms -> hourOf(ms) })
    private val ux = UxRestrictionWatcher(app)
    private val realVehicle: VehicleSource = PleosVehicleSource(app)
    private val realNavi: NaviGateway = PleosNaviGateway(app)
    private var demo: DemoDrive? = null

    // 시연 모드는 실제 1초를 주행 1분으로 압축한다. 판단 로직(쿨다운·내기순환 시간 등)도 같은 시계를 쓴다.
    private var demoStartReal = 0L
    private var demoBase = 0L
    private fun now(): Long =
        if (demo != null) demoBase + (System.currentTimeMillis() - demoStartReal) * DEMO_SPEED else System.currentTimeMillis()
    private fun hourOf(ms: Long): Int = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)

    private var napEndsAtReal: Long? = null
    private val tone by lazy { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 90) }.getOrNull() }

    private var settings = Settings()
    private var reports: List<Report> = emptyList()
    private var lastDrivingDrowsyScore = 0.0

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val vehicle: VehicleSource get() = demo?.vehicle ?: realVehicle
    private val navi: NaviGateway get() = demo?.navi ?: realNavi

    init {
        viewModelScope.launch {
            val (s, r) = withContext(Dispatchers.IO) { store.load() }
            settings = s; reports = r
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

    private fun tick() {
        val snapshot = vehicle.snapshot.value
        val route = navi.route.value
        val f = planner.features(snapshot, route)
        val drowsy = RiskModel.drowsiness(f)
        val motion = RiskModel.motionSickness(f)
        if (snapshot.gear == GearState.DRIVE) lastDrivingDrowsyScore = drowsy

        val passenger = settings.passengerProfileId?.takeIf { hasPassenger(snapshot) || demo != null }
        val newSuggestion = planner.evaluate(snapshot, route, reports, DRIVER, passenger, restStops.stops, settings.caffeineOk)
        val gearDriving = snapshot.gear == GearState.DRIVE || snapshot.gear == GearState.REVERSE
        // 실제 차량에서는 플랫폼의 주행 제한 상태를 따르고, 시연 모드에서는 가상 기어를 따른다.
        val driving = if (demo != null) gearDriving else ux.restricted.value ?: gearDriving

        val napLeft = napEndsAtReal?.let { it - System.currentTimeMillis() }
        if (napLeft != null && napLeft <= 0) {
            napEndsAtReal = null
            runCatching { tone?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3_000) }
            showMessage("15분이 지났어요. 천천히 일어나 몸을 풀고 출발하세요")
        }

        _ui.update {
            it.copy(
                driving = driving,
                demo = demo != null,
                vehicle = snapshot,
                route = route,
                features = f,
                motionScore = motion,
                drowsyScore = drowsy,
                suggestion = newSuggestion ?: it.suggestion,
                napSecondsLeft = napEndsAtReal?.let { end -> ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0) },
                simulatedClock = if (demo != null) clockText(now()) else null,
                askDrowsiness = planner.pendingDrowsinessQuestion,
                settings = settings,
                personal = personalInfo(),
                vehicleStatus = vehicle.status.value,
                naviStatus = navi.status.value,
                restStopCount = restStops.stops.size,
            )
        }
    }

    private fun hasPassenger(s: VehicleSnapshot) = s.occupiedSeats.any { it != app.shimpyo.domain.SeatPos.DRIVER }

    private fun personalInfo(): List<PersonalInfo> = settings.profiles.flatMap { p ->
        Condition.entries.mapNotNull { c ->
            val relevant = (c == Condition.DROWSINESS && p.id == DRIVER) || (c == Condition.MOTION_SICKNESS && p.id != DRIVER)
            if (!relevant) null else PersonalInfo(
                profileName = p.name,
                condition = c,
                count = personalizer.reportCount(reports, c, p.id),
                threshold = personalizer.threshold(reports, c, p.id),
                personalized = personalizer.isPersonalized(reports, c, p.id),
            )
        }
    }

    // ---- 사용자 입력 ----

    /** 동승자 쪽에서 누르는 "멀미나요". 기록을 남기고 바로 다음 쉼터를 제안한다. */
    fun reportMotionSickness() {
        val profile = settings.passengerProfileId ?: return showMessage("정차 중에 멀미 기록을 연결할 동승자를 먼저 골라 주세요")
        val f = planner.lastFeatures ?: return
        addReport(Report(now(), Condition.MOTION_SICKNESS, profile, RiskModel.motionSickness(f), f.minutesSinceStart))
        val pick = navi.route.value.location?.let { RestStopSelector.nextAhead(restStops.stops, it) }
        _ui.update {
            it.copy(
                suggestion = Suggestion(Condition.MOTION_SICKNESS, profile, pick, Advice.forCondition(Condition.MOTION_SICKNESS, settings.caffeineOk), RiskModel.motionSickness(f), personalizer.threshold(reports, Condition.MOTION_SICKNESS, profile)),
            )
        }
    }

    /** 동승자가 "운전자가 졸려 보여요"를 눌렀을 때. */
    fun reportDriverLooksSleepy() {
        val f = planner.lastFeatures ?: return
        addReport(Report(now(), Condition.DROWSINESS, DRIVER, RiskModel.drowsiness(f), f.minutesSinceStart))
        val pick = navi.route.value.location?.let { RestStopSelector.nextAhead(restStops.stops, it) }
        _ui.update {
            it.copy(suggestion = Suggestion(Condition.DROWSINESS, DRIVER, pick, Advice.forCondition(Condition.DROWSINESS, settings.caffeineOk), RiskModel.drowsiness(f), personalizer.threshold(reports, Condition.DROWSINESS, DRIVER)))
                
        }
    }

    /** 쉼터에서 15분 쪽잠 알람. 15분이 지나면 알림음과 함께 깨운다. */
    fun startNap() {
        napEndsAtReal = System.currentTimeMillis() + NAP_MS
        tick()
    }

    fun cancelNap() {
        napEndsAtReal = null
        tick()
    }

    fun clearRestAdvice() = _ui.update { it.copy(restAdvice = null) }

    /** 주차 후 "아까 졸렸나요?" 응답. */
    fun answerDrowsiness(sleepy: Boolean) {
        if (sleepy) {
            addReport(Report(now(), Condition.DROWSINESS, DRIVER, lastDrivingDrowsyScore, planner.lastFeatures?.minutesSinceStart ?: 0.0))
            showMessage("기록했어요. 다음 주행부터 이 기록을 반영해 더 일찍 쉼터를 제안해요")
        }
        planner.answeredDrowsinessQuestion()
    }

    fun acceptSuggestion() {
        val s = _ui.value.suggestion ?: return
        s.stop?.let { navi.addWaypoint(it.stop) } ?: showMessage("가까운 쉼터를 찾지 못했어요. 가능한 안전한 곳에 정차해 주세요")
        // 받아들인 쉼터의 할 일을 주차 후 정차 화면에서 다시 보여 준다.
        _ui.update { it.copy(suggestion = null, restAdvice = s) }
    }

    fun dismissSuggestion() = _ui.update { it.copy(suggestion = null) }

    fun selectPassenger(profileId: String?) = updateSettings(settings.copy(passengerProfileId = profileId))

    fun setCaffeineOk(ok: Boolean) = updateSettings(settings.copy(caffeineOk = ok))

    fun toggleDemo() {
        val d = demo
        planner.reset()
        _ui.update { it.copy(suggestion = null, restAdvice = null) }
        if (d == null) {
            // 시연 시나리오: 오후 1시 출발 → 굽은 길(멀미) → 오후 2시 무렵 단조로운 고속도로(졸음) → 쉼터 주차
            demoBase = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 13); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
            demoStartReal = System.currentTimeMillis()
            demo = DemoDrive().also { it.vehicle.start() }
        } else {
            d.vehicle.stop(); demo = null
        }
    }

    private fun clockText(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$ampm $h12:${"%02d".format(c.get(java.util.Calendar.MINUTE))}"
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun showMessage(m: String) = _ui.update { it.copy(message = m) }

    private fun addReport(r: Report) {
        reports = reports + r
        persist()
    }

    private fun updateSettings(s: Settings) {
        settings = s
        persist()
    }

    private fun persist() {
        val s = settings; val r = reports
        viewModelScope.launch(Dispatchers.IO) { store.save(s, r) }
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
