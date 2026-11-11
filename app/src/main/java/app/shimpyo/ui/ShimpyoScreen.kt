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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.shimpyo.domain.Advice
import app.shimpyo.domain.Condition
import app.shimpyo.domain.Suggestion
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ShimpyoScreen(state: UiState, vm: MainViewModel) {
    // Surface가 글자 기본색(onBackground)을 정해 주므로 어두운 배경에서도 글자가 보인다.
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
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
 * 주행 중 화면: 카드 1장과 큰 버튼만. 목록·지도·긴 글은 보여 주지 않는다.
 */
@Composable
private fun DrivingScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.demo) DemoBadge()
        val s = state.suggestion
        if (s != null) {
            SuggestionCard(s, onAccept = vm::acceptSuggestion, onDismiss = vm::dismissSuggestion)
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(28.dp)) {
                    Text("쉼표가 함께 달리고 있어요", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("쉬어 갈 때가 되면 먼저 알려 드릴게요", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("멀미나요", Modifier.weight(1f), onClick = vm::reportMotionSickness)
            BigButton("운전자가 졸려 보여요", Modifier.weight(1f), secondary = true, onClick = vm::reportDriverLooksSleepy)
        }
        Text(
            "버튼은 동승자가 눌러 주세요. 운전자는 주차 후에 한 번만 물어봐요.",
            fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(28.dp)) {
            Text(Advice.title(s.condition), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(8.dp))
            val where = s.stop?.let { "${String.format(Locale.KOREA, "%.0f", it.distanceKm)}km 뒤 ${it.stop.name}" } ?: "가장 가까운 안전한 곳"
            Text(where, fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(6.dp))
            // 주행 중에는 할 일 1개만 보여 준다. 나머지는 쉼터에 도착해 정차 화면에서 본다.
            Text(s.actions.first(), fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.height(80.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary),
                ) { Text(if (s.stop != null) "경유지로 추가" else "확인", fontSize = 24.sp) }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.height(80.dp).width(180.dp)) {
                    Text("괜찮아요", fontSize = 22.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, modifier: Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        colors = if (secondary) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
        else ButtonDefaults.buttonColors(),
    ) { Text(label, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DemoBadge() {
    Text(
        "시연 모드 · 가상 주행 데이터",
        Modifier.background(MaterialTheme.colorScheme.secondary).padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSecondary, fontSize = 18.sp,
    )
}

/**
 * 정차 화면: 동승자 프로필 선택, 주차 후 졸음 응답, 카페인 설정, 개인화 현황, 연결 상태.
 */
@Composable
private fun ParkedScreen(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("쉼표", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("쉬어야 할 때를 미리 계획하는 차량용 앱", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.demo) DemoBadge()

        if (state.askDrowsiness) {
            Section("아까 운전하면서 졸렸나요?") {
                Text("답해 주시면 다음 주행부터 더 일찍 쉼터를 제안해요.", fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { vm.answerDrowsiness(true) }, modifier = Modifier.height(72.dp).weight(1f)) { Text("네, 졸렸어요", fontSize = 22.sp) }
                    OutlinedButton(onClick = { vm.answerDrowsiness(false) }, modifier = Modifier.height(72.dp).weight(1f)) { Text("아니요", fontSize = 22.sp) }
                }
            }
        }

        state.suggestion?.let { s ->
            Section("쉼터에서 할 일") {
                s.actions.forEach { Text("· $it", fontSize = 18.sp) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::dismissSuggestion) { Text("닫기") }
            }
        }

        Section("멀미 기록을 연결할 동승자") {
            Text("앱은 어느 자리에 사람이 있는지만 알아요. 출발 전에 누가 탔는지 골라 주세요.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.settings.profiles.filter { it.id != MainViewModel.DRIVER }.forEach { p ->
                    FilterChip(selected = state.settings.passengerProfileId == p.id, onClick = { vm.selectPassenger(p.id) }, label = { Text(p.name, fontSize = 20.sp) })
                }
                FilterChip(selected = state.settings.passengerProfileId == null, onClick = { vm.selectPassenger(null) }, label = { Text("동승자 없음", fontSize = 20.sp) })
            }
        }

        Section("카페인") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("커피를 마실 수 있어요", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(checked = state.settings.caffeineOk, onCheckedChange = vm::setCaffeineOk)
            }
            Text("끄면 쪽잠과 무카페인 보조 수단으로 안내해요.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("나에게 맞춘 기준") {
            state.personal.forEach { p ->
                val label = if (p.condition == Condition.DROWSINESS) "졸음" else "멀미"
                val how = if (p.personalized) "개인 기록 ${p.count}회 반영" else "기본 규칙 (기록 ${p.count}/3회)"
                Text("${p.profileName} · $label · 기준점 ${pct(p.threshold)} · $how", fontSize = 18.sp)
            }
            Text("기록이 3회 이상 쌓이면, 힘들다고 알린 순간보다 조금 이른 지점에서 제안해요.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("지금 상태") {
            val v = state.vehicle
            Text("기어 ${v.gear} · 조향 ${v.steeringAngleDeg ?: "-"}° · 브레이크 ${v.brakePressed ?: "-"} · 실내 ${v.cabinTempC ?: "-"}℃ · 내기순환 ${v.recirculationOn ?: "-"} · 주행 ${v.tripDrivingMinutes ?: "-"}분", fontSize = 16.sp)
            Text("착석: ${v.occupiedSeats.joinToString()}", fontSize = 16.sp)
            Text("멀미 점수 ${pct(state.motionScore)} · 졸음 점수 ${pct(state.drowsyScore)}", fontSize = 16.sp)
            Text(state.vehicleStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.naviStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("쉼터 데이터 ${state.restStopCount}곳 · © OpenStreetMap contributors (ODbL)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(onClick = vm::toggleDemo, modifier = Modifier.height(64.dp)) {
            Text(if (state.demo) "시연 모드 끄기" else "시연 모드 켜기(가상 주행 재생)", fontSize = 20.sp)
        }
        Text("쉼표는 진단하지 않고, 차를 제어하지 않으며, 기록은 차 안에만 저장해요.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun pct(v: Double) = "${(v * 100).toInt()}%"
