package app.shimpyo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shimpyo.MainViewModel
import app.shimpyo.UiState
import app.shimpyo.domain.CarePlan
import app.shimpyo.domain.CareStage
import app.shimpyo.domain.CareText
import app.shimpyo.domain.Condition
import app.shimpyo.domain.PersonState
import app.shimpyo.domain.RestFeedback
import app.shimpyo.domain.Role
import app.shimpyo.domain.SeatPos
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
fun ShimpyoScreen(state: UiState, vm: MainViewModel) {
    // Surface가 글자 기본색(onBackground)을 정해 주므로 어두운 배경에서도 글자가 보인다.
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            if (state.driving) DrivingScreen(state, vm) else ParkedScreen(state, vm)
            state.message?.let { msg ->
                LaunchedEffect(msg) { delay(5_000); vm.clearMessage() }
                Card(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) { Text(msg, Modifier.padding(16.dp), fontSize = 20.sp) }
            }
        }
    }
}

/**
 * 주행 중 화면: 사람별 상태 한 줄, 챙김 카드 1장, 동승자 버튼 1개. 목록·지도·숫자 점수는 보여 주지 않는다.
 */
@Composable
private fun DrivingScreen(state: UiState, vm: MainViewModel) {
    val plan = state.plan
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.demo) DemoBadge(state.simulatedClock)
        PeopleRow(plan.people)

        val heading = state.heading
        when {
            plan.stage == CareStage.CARE -> CareCard(plan, onAccept = vm::acceptPlan, onDismiss = vm::dismissPlan)
            heading != null -> QuietCard(
                "${heading.choice?.stop?.stop?.name ?: "쉼터"}로 가고 있어요",
                "${heading.focus?.passenger?.label ?: "모두"}에 맞춰 함께 쉬어 가요. 길 안내는 내비가 해요.",
            )
            plan.stage == CareStage.HEADS_UP -> QuietCard(CareText.headsUp(plan), "쉬어 갈 곳은 때가 되면 골라 드릴게요.", accent = true)
            else -> QuietCard(CareText.statusLine(plan), "쉼표가 모두를 지켜보고 있어요.")
        }

        Spacer(Modifier.weight(1f))
        if (state.choosingWho) {
            Text("누가 불편한가요?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                state.passengers.sortedBy { it.role == Role.DRIVER }.forEach { p ->
                    BigButton(if (p.role == Role.DRIVER) "운전자님이 졸려 보여요" else p.label, Modifier.weight(1f)) { vm.reportNow(p) }
                }
                OutlinedButton(onClick = vm::closeWho, modifier = Modifier.height(120.dp).width(160.dp)) { Text("취소", fontSize = 22.sp) }
            }
        } else {
            BigButton("지금 불편해요", Modifier.fillMaxWidth(), secondary = true, onClick = vm::openWho)
            Text("버튼은 동승자가 눌러 주세요. 운전자에게는 쉼터에 섰을 때 한 번만 물어봐요.", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeopleRow(people: List<PersonState>) {
    if (people.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        people.forEach { ps ->
            val mood = CareText.mood(ps)
            val color = when (mood) {
                "곧 쉬어야 해요" -> MaterialTheme.colorScheme.tertiary
                "조금씩 지쳐 가요" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.surface
            }
            Surface(color = color, shape = RoundedCornerShape(24.dp)) {
                Text("${ps.passenger.label} · $mood", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun QuietCard(title: String, body: String, accent: Boolean = false) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(28.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 챙김 카드: 사람 → 이유 → 기회 → 행동 하나. */
@Composable
private fun CareCard(plan: CarePlan, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val on = MaterialTheme.colorScheme.onPrimary
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(28.dp)) {
            Text(CareText.cardTitle(plan), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = on)
            Spacer(Modifier.height(10.dp))
            Text(CareText.cardReason(plan), fontSize = 24.sp, color = on)
            Spacer(Modifier.height(4.dp))
            Text(CareText.cardOpportunity(plan), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = on)
            CareText.companionLine(plan)?.let { Spacer(Modifier.height(4.dp)); Text(it, fontSize = 20.sp, color = on) }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.height(84.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = on, contentColor = MaterialTheme.colorScheme.primary),
                ) { Text(CareText.button(plan), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.height(84.dp).width(170.dp)) {
                    Text("다음에", fontSize = 22.sp, color = on)
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, modifier: Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(120.dp),
        colors = if (secondary) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        else ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DemoBadge(clock: String?) {
    Text(
        "시연 모드 · 가상 주행 데이터" + (clock?.let { " · $it" } ?: ""),
        Modifier.background(MaterialTheme.colorScheme.secondary).padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSecondary, fontSize = 18.sp,
    )
}

/**
 * 정차 화면: 쉼터 도착 질문, 그 사람을 위한 할 일, 출발 전 탑승자 고르기, 사람별 리듬, 연결 상태.
 */
@Composable
private fun ParkedScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("쉼표", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("모든 탑승자를 챙기는 차", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.demo) DemoBadge(state.simulatedClock)

        state.arrival?.let { q ->
            Section(CareText.arrivalQuestion(q), highlight = true) {
                Text("한 번만 답해 주시면 다음 여정은 이 리듬에 맞춰 챙겨요.", fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val labels = if (q.person.role == Role.DRIVER && !q.viaPlan)
                        listOf(RestFeedback.ALREADY_UNCOMFORTABLE to "네, 졸렸어요", RestFeedback.JUST_RIGHT to "조금 피곤했어요", RestFeedback.COULD_GO_ON to "괜찮았어요")
                    else listOf(RestFeedback.ALREADY_UNCOMFORTABLE to "이미 불편했어요", RestFeedback.JUST_RIGHT to "딱 좋았어요", RestFeedback.COULD_GO_ON to "더 갈 수 있었어요")
                    labels.forEach { (fb, label) ->
                        Button(onClick = { vm.answerArrival(fb) }, modifier = Modifier.height(76.dp).weight(1f)) { Text(label, fontSize = 22.sp) }
                    }
                }
            }
        }

        state.restCare?.let { rc ->
            Section("${rc.place}에서 ${CareText.objectOf(rc.person)} 위해 할 일") {
                CareText.careActions(rc.person, state.settings.caffeineOk).forEach { Text("· $it", fontSize = 20.sp) }
                if (rc.person.condition == Condition.DROWSINESS) NapControls(state, vm)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::clearRestCare) { Text("닫기") }
            }
        }
        Section("오늘 함께 탄 사람") {
            Text("차는 어느 자리에 사람이 있는지만 알아요. 출발 전에 누가 앉았는지 골라 주세요.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            listOf(SeatPos.FRONT_PASSENGER to "조수석", SeatPos.REAR_LEFT to "뒷좌석 왼쪽", SeatPos.REAR_RIGHT to "뒷좌석 오른쪽").forEach { (seat, name) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(name, fontSize = 20.sp, modifier = Modifier.width(170.dp))
                    val current = state.settings.seats[seat]
                    FilterChip(selected = current == null, onClick = { vm.setSeat(seat, null) }, label = { Text("비어 있음", fontSize = 18.sp) })
                    FilterChip(selected = current == Role.CHILD, onClick = { vm.setSeat(seat, Role.CHILD) }, label = { Text("아이", fontSize = 18.sp) })
                    FilterChip(selected = current == Role.ADULT, onClick = { vm.setSeat(seat, Role.ADULT) }, label = { Text("어른", fontSize = 18.sp) })
                }
            }
        }

        Section("사람별 이동 리듬") {
            state.plan.people.forEach { ps -> RhythmLine(ps, state) }
            if (state.plan.people.isEmpty()) Text("출발하면 사람별 여유를 계산해요.", fontSize = 18.sp)
            Text(
                "쉼터에 설 때마다 받은 응답으로 사람마다 '편안함 통'의 크기를 좁혀 가요. 부담이 쌓이는 속도는 연구 방향에 맞춘 가정값이에요.",
                fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("카페인") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("커피를 마실 수 있어요", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.settings.caffeineOk, onCheckedChange = vm::setCaffeineOk)
            }
            Text("끄면 쪽잠과 무카페인 보조 수단으로 안내해요.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("지금 상태") {
            val v = state.vehicle
            Text("기어 ${v.gear} · 속도 ${v.speedKph?.let { "%.0f".format(it) } ?: "-"}km/h · 조향 ${v.steeringAngleDeg ?: "-"}° · 브레이크 ${v.brakePressed ?: "-"} · 실내 ${v.cabinTempC ?: "-"}℃ · 내기순환 ${v.recirculationOn ?: "-"}", fontSize = 16.sp)
            Text("착석: ${v.occupiedSeats.joinToString()} · 앞 회전 안내 ${state.route.upcomingTurnDistancesM.size}개", fontSize = 16.sp)
            Text(state.vehicleStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.naviStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("쉼터 데이터 ${state.restStopCount}곳 · © OpenStreetMap contributors (ODbL)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(onClick = vm::toggleDemo, modifier = Modifier.height(64.dp)) {
            Text(if (state.demo) "시연 모드 끄기" else "시연 모드 켜기(가상 주행 재생)", fontSize = 20.sp)
        }
        Text("쉼표는 진단하지 않고, 차를 제어하지 않으며, 기록은 차 안에만 저장해요. 길 안내는 내비가 해요.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NapControls(state: UiState, vm: MainViewModel) {
    Spacer(Modifier.height(12.dp))
    val left = state.napSecondsLeft
    if (left == null) {
        Button(onClick = vm::startNap, modifier = Modifier.height(72.dp)) { Text("15분 쪽잠 알람 시작", fontSize = 22.sp) }
    } else {
        Text("쪽잠 중 · ${left / 60}:${"%02d".format(left % 60)} 뒤에 깨워 드려요", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = vm::cancelNap) { Text("알람 끄기") }
    }
}

@Composable
private fun RhythmLine(ps: PersonState, state: UiState) {
    val c = state.calibrations[ps.passenger.key]
    val what = if (ps.passenger.condition == Condition.DROWSINESS) "졸음" else "멀미"
    val cap = if (c == null) "기본값으로 시작" else "통 크기 약 ${pct(c.lo)}~${pct(c.hi)} · 응답 ${c.answers}회"
    Text("${ps.passenger.label} · $what · 지금 ${pct(ps.load)} 참 · 여유 ${CareText.marginRange(ps)} · $cap", fontSize = 18.sp)
}

@Composable
private fun Section(title: String, highlight: Boolean = false, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun pct(v: Double) = "${ceil(v * 100).toInt()}%"
