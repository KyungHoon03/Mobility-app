package app.shimpyo.domain

import kotlin.math.min

/**
 * 연구 근거로 만든 기본 규칙 점수(0..1). 첫날부터 동작하고, 개인 기록이 쌓이면 [Personalizer]가 기준점을 조정한다.
 * 멀미와 졸음은 판정 규칙을 따로 둔다.
 */
object RiskModel {

    /**
     * 멀미 점수.
     * - 주행 변화(흔들림 대리지표): Souman 외 2026, Henry 외 2023
     * - 앞으로의 굽은 길·회전: Schmidt 외 2020, Metzulat 외 2025
     * - 출발 후 경과 시간(증상 시작 대부분 30분 이내 응답): Metzulat 외 2025
     * 실내 온도는 멀미 근거가 확인되지 않아 넣지 않는다.
     */
    fun motionSickness(f: Features): Double =
        (0.50 * f.drivingChange +
            0.35 * f.curveAhead +
            0.15 * min(f.minutesSinceStart / 30.0, 1.0)).coerceIn(0.0, 1.0)

    /**
     * 졸음 점수.
     * - 연속 운전 시간: Zeller 외 2020, Horne·Reyner 1999
     * - 시간대(새벽 2~6시, 오후 2~4시): Horne·Reyner 1995
     * - 단조로운 주행(주행 변화가 적음): Horne·Reyner 1995
     * - 중립보다 약간 따뜻한 실내: Sunagawa 외 2023(초록 기준)
     * - 내기순환 지속(보조 신호, 연구 결과가 엇갈림): Hudda·Fruin 2017, Chen 외 2020, Wang 외 2025
     */
    fun drowsiness(f: Features): Double {
        val time = min(f.minutesSinceStart / 120.0, 1.0)
        val circadian = if (f.hourOfDay in 2..5 || f.hourOfDay in 14..15) 1.0 else 0.0
        val monotony = 1.0 - f.drivingChange
        val warm = slightlyWarm(f.cabinTempC)
        val recirculation = min(f.recirculationMinutes / 60.0, 1.0)
        return (0.35 * time + 0.20 * circadian + 0.20 * monotony + 0.15 * warm + 0.10 * recirculation)
            .coerceIn(0.0, 1.0)
    }

    /** 약 24~27℃를 1로 두는 근사치. 쾌적 지수(PMV)를 계산하는 값은 아니다. */
    fun slightlyWarm(tempC: Double?): Double = when {
        tempC == null -> 0.0
        tempC < 21.0 -> 0.0
        tempC < 24.0 -> (tempC - 21.0) / 3.0
        tempC <= 27.0 -> 1.0
        tempC < 30.0 -> (30.0 - tempC) / 3.0
        else -> 0.0
    }

    /** 앞 3km 안 회전 안내 4개 이상이면 1. */
    fun curveAhead(upcomingTurnDistancesM: List<Double>, withinM: Double = 3_000.0): Double =
        min(upcomingTurnDistancesM.count { it in 0.0..withinM } / 4.0, 1.0)

    fun score(condition: Condition, f: Features): Double = when (condition) {
        Condition.MOTION_SICKNESS -> motionSickness(f)
        Condition.DROWSINESS -> drowsiness(f)
    }
}

/**
 * 본인 신호로 기준점을 사람마다 맞춘다.
 * 같은 사람의 같은 상태 기록이 [minReports]회 이상이면, 그 사람이 힘들다고 알린 순간 점수의 평균보다
 * 조금 낮은 지점을 새 기준점으로 삼아 더 일찍 제안한다.
 */
class Personalizer(
    val defaultThreshold: Double = 0.60,
    private val minReports: Int = 3,
    private val earlyFactor: Double = 0.85,
    private val floor: Double = 0.25,
) {
    fun threshold(reports: List<Report>, condition: Condition, profileId: String): Double {
        val mine = reports.filter { it.condition == condition && it.profileId == profileId }
        if (mine.size < minReports) return defaultThreshold
        val mean = mine.takeLast(10).map { it.score }.average()
        return (mean * earlyFactor).coerceIn(floor, defaultThreshold)
    }

    fun reportCount(reports: List<Report>, condition: Condition, profileId: String): Int =
        reports.count { it.condition == condition && it.profileId == profileId }

    fun isPersonalized(reports: List<Report>, condition: Condition, profileId: String): Boolean =
        reportCount(reports, condition, profileId) >= minReports
}
