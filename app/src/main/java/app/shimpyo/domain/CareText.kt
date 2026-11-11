package app.shimpyo.domain

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 케어 문장. 사람 → 이유 → 기회 → 행동 순서로 말하고, 점수·경고·의학 용어는 쓰지 않는다.
 * 거리와 길 안내는 내비가 맡으므로 문장의 주인공은 항상 사람이다.
 */
object CareText {

    private fun min(m: Double): Int = ceil(m).toInt().coerceAtLeast(1)

    /** 받침 여부에 따라 조사를 고른다(이/가, 은/는). */
    private fun particle(word: String, withBatchim: String, without: String): String {
        val last = word.lastOrNull() ?: return without
        if (last !in '가'..'힣') return without
        return if ((last - '가') % 28 != 0) withBatchim else without
    }

    fun subject(p: Passenger) = p.label + particle(p.label, "이", "가")
    fun topic(p: Passenger) = p.label + particle(p.label, "은", "는")
    fun objectOf(p: Passenger) = p.label + particle(p.label, "을", "를")

    /** 지켜봄 단계 상태 한 줄. */
    fun statusLine(plan: CarePlan): String {
        val next = plan.focus?.margin?.point
        return if (next == null) "모두 편안해요 · 당분간 쉬지 않아도 괜찮아요"
        else "모두 편안해요 · 다음 휴식은 약 ${min(next)}분 뒤"
    }

    /** 미리 알림 한 줄(버튼 없음). */
    fun headsUp(plan: CarePlan): String {
        val f = plan.focus ?: return statusLine(plan)
        return "30분 안에 한 번 쉬어 가면 좋아요 (${f.passenger.label} 기준)"
    }

    fun cardTitle(plan: CarePlan): String {
        val p = plan.focus?.passenger ?: return "잠시 쉬어 가요"
        return when {
            plan.urgent && p.role == Role.DRIVER -> "운전자님이 졸려 보여요"
            plan.urgent -> "${subject(p)} 불편해해요"
            p.role == Role.DRIVER -> "운전자님, 졸음이 오기 쉬운 때가 다가와요"
            else -> "${subject(p)} 곧 힘들어질 수 있어요"
        }
    }

    fun cardReason(plan: CarePlan): String {
        val p = plan.focus?.passenger ?: return ""
        if (plan.urgent) return "가장 가까운 쉼터에서 함께 쉬어 가요."
        return when (p.condition) {
            Condition.MOTION_SICKNESS -> {
                val c = plan.minutesToCurvy
                when {
                    c != null && c < 1.0 -> "지금 굽은 길이 이어지고 있어요."
                    c != null -> "약 ${min(c)}분 뒤 굽은 길이 이어져요."
                    else -> "이동이 길어지면서 많이 흔들렸어요."
                }
            }
            Condition.DROWSINESS -> {
                val d = plan.continuousDrivingMin.roundToInt()
                val drove = when {
                    d >= 60 -> "${d / 60}시간 ${d % 60}분째 쉬지 않고 달렸고, "
                    d >= 30 -> "${d}분째 달리고 있고, "
                    else -> ""
                }
                val time = when (plan.hour) {
                    in 14..15 -> "졸음이 오기 쉬운 오후 2~4시예요."
                    in 2..5 -> "졸음이 오기 쉬운 새벽이에요."
                    else -> "단조로운 길과 따뜻한 실내가 이어지고 있어요."
                }
                "$drove$time"
            }
        }
    }

    fun cardOpportunity(plan: CarePlan): String {
        val c = plan.choice ?: return "근처에 쉼터가 보이지 않아요. 안전한 곳에 잠시 멈춰 주세요."
        val name = c.stop.stop.name
        val eta = min(c.stop.etaMin)
        return when {
            plan.urgent -> "약 ${eta}분 뒤 닿는 ${name}에서 쉬어 가요."
            c.late -> "가장 가까운 ${name}까지 약 ${eta}분이에요. 그곳에서 바로 쉬어 가요."
            c.beforeCurvy -> "약 ${eta}분 뒤 닿는 ${name}${particle(name, "이", "가")} 굽은 길 전 마지막 휴식 기회예요."
            c.isLastBeforeDeadline -> "약 ${eta}분 뒤 닿는 ${name}${particle(name, "이", "가")} 힘들어지기 전 마지막 편안한 휴식 기회예요."
            else -> "약 ${eta}분 뒤 닿는 ${name}에서 쉬어 가면 좋아요."
        }
    }

    fun companionLine(plan: CarePlan): String? {
        val c = plan.companion?.passenger ?: return null
        return "${c.label}도 함께 쉬면 좋아요."
    }

    fun button(plan: CarePlan): String = plan.choice?.let { "${it.stop.stop.name}에서 함께 쉬기" } ?: "확인"

    /** 주행 중 사람별 칩에 붙는 말. 숫자 대신 상태만. */
    fun mood(ps: PersonState): String {
        val m = ps.margin.point
        return when {
            m == null || m > 45 -> "편안해요"
            m > 20 -> "조금씩 지쳐 가요"
            else -> "곧 쉬어야 해요"
        }
    }

    fun marginRange(ps: PersonState): String {
        val lo = ps.margin.low
        val hi = ps.margin.high
        return when {
            lo == null -> "2시간 넘게 여유"
            hi == null -> "약 ${min(lo)}분 이상"
            else -> "약 ${min(lo)}~${min(hi)}분"
        }
    }

    fun arrivalQuestion(q: ArrivalQuestion): String =
        if (q.viaPlan) "도착했을 때 ${topic(q.person)} 어땠나요?"
        else if (q.person.role == Role.DRIVER) "운전하는 동안 졸리진 않았나요?"
        else "오는 동안 ${topic(q.person)} 어땠나요?"

    /** 쉼터에서 할 일. 의료 효과를 약속하지 않고 연구에서 확인된 범위만 안내한다. */
    fun careActions(p: Passenger, caffeineOk: Boolean): List<String> = when (p.condition) {
        Condition.DROWSINESS -> buildList {
            add("15분 이내로 쪽잠을 자 보세요. 알람을 15분에 맞춰 드려요.")
            if (caffeineOk) add("커피를 마신 뒤 바로 쪽잠을 자면 가장 효과적이라는 연구가 있어요.")
            else add("카페인이 맞지 않으면 쪽잠만으로도 도움이 돼요. 무카페인 껌이나 페퍼민트 향은 보조로만 쓰세요.")
            add("에너지음료나 단 음료는 권하지 않아요. 나중에 반응이 느려졌다는 연구가 있어요.")
        }
        Condition.MOTION_SICKNESS -> listOf(
            "차 밖으로 나와 바람을 쐬며 잠시 쉬어요.",
            "다시 탈 때는 휴대폰이나 책을 내려놓고 창밖 멀리 보기",
            "가능하면 앞좌석으로 옮겨 앉기",
            "달리는 동안 창문을 조금 열어 두기",
        )
    }
}
