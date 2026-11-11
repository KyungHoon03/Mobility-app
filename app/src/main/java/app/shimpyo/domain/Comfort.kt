package app.shimpyo.domain

import kotlin.math.max
import kotlin.math.min

/**
 * 편안함 통: 사람마다 쉬지 않고 견딜 수 있는 부담의 크기를 1.0 근처로 두고,
 * 이동하는 동안 1분마다 얼마나 차는지(부담 속도)를 계산한다. 쉬면 비워진다.
 *
 * 부담 속도의 "방향"은 연구 근거를 따르지만, 숫자(분당 몇 %)는 우리가 정한 가정값이다.
 * 사람마다 다른 부분은 [Calibration]이 쉼터 응답으로 맞춘다.
 */
object ComfortRates {

    /**
     * 멀미 부담 속도(분당).
     * - 굽은 길·회전에서 가장 잦음: Schmidt 외 2020, Metzulat 외 2025
     * - 반복되는 흔들림(가다 서다)이 키움: Souman 외 2026, Henry 외 2023 → 조향·브레이크 대리지표
     * - 뒷좌석에서 가장 잦음: Schmidt 외 2020
     */
    fun motion(curvyFraction: Double, drivingChange: Double, rearSeat: Boolean): Double {
        val road = 0.008 + 0.038 * curvyFraction.coerceIn(0.0, 1.0)
        val stopGo = 0.03 * drivingChange.coerceIn(0.0, 1.0)
        val seat = if (rearSeat) 1.3 else 1.0
        return (road + stopGo) * seat
    }

    /**
     * 졸음 부담 속도(분당).
     * - 연속 운전 시간이 길수록: Zeller 외 2020(초록), Horne·Reyner 1999
     * - 새벽 2~6시, 오후 2~4시: Horne·Reyner 1995
     * - 단조로운 주행: Horne·Reyner 1995 → 주행 변화가 적을수록
     * - 중립보다 약간 따뜻한 실내: Sunagawa 외 2023(초록)
     * - 내기순환 지속(보조 신호): Hudda·Fruin 2017
     */
    fun drowsy(continuousMin: Double, hour: Int, drivingChange: Double, cabinTempC: Double?, recirculationMin: Double): Double {
        val time = 0.004 * min(continuousMin / 60.0, 2.0)
        val circadian = if (hour in 2..5 || hour in 14..15) 0.010 else 0.0
        val monotony = 0.004 * (1.0 - drivingChange.coerceIn(0.0, 1.0))
        val warm = 0.003 * slightlyWarm(cabinTempC)
        val recirculation = 0.002 * min(recirculationMin / 60.0, 1.0)
        return 0.004 + time + circadian + monotony + warm + recirculation
    }

    /** 쉬는 동안 통이 비워지는 속도(분당). 15분 쉬면 대부분 비워진다고 가정한다. */
    const val RECOVERY_PER_MIN = 0.06

    /** 약 24~27℃를 1로 두는 근사치. 쾌적 지수(PMV)를 계산하는 값은 아니다. */
    fun slightlyWarm(tempC: Double?): Double = when {
        tempC == null -> 0.0
        tempC < 21.0 -> 0.0
        tempC < 24.0 -> (tempC - 21.0) / 3.0
        tempC <= 27.0 -> 1.0
        tempC < 30.0 -> (30.0 - tempC) / 3.0
        else -> 0.0
    }
}

/**
 * 앞으로의 길. NaviHelper는 경로 좌표선을 주지 않으므로, 회전 안내(TBT)가 3km 안에 3개 이상 몰린 구간을
 * "굽은 길이 이어지는 구간"으로 보고, 차 속도로 몇 분 뒤인지 바꾼다.
 */
class RouteAhead(turnDistancesM: List<Double>, private val speedKph: Double) {
    /** 굽은 구간(m) 목록: [시작, 끝]. */
    val curvyRangesM: List<ClosedFloatingPointRange<Double>> = curvyRanges(turnDistancesM.filter { it >= 0 }.sorted())

    private val metersPerMin: Double get() = max(speedKph, MIN_SPEED_KPH) * 1000.0 / 60.0

    /** t분 뒤에 굽은 구간 안에 있는가. */
    fun isCurvyAt(minute: Double): Boolean {
        val m = minute * metersPerMin
        return curvyRangesM.any { m in it }
    }

    /** t분부터 1분 동안 굽은 구간 안에 있는 비율(0..1). */
    fun curvyFraction(minute: Double): Double {
        val a = minute * metersPerMin
        val b = a + metersPerMin
        val inside = curvyRangesM.sumOf { r -> max(0.0, minOf(b, r.endInclusive) - maxOf(a, r.start)) }
        return (inside / metersPerMin).coerceIn(0.0, 1.0)
    }

    /** 다음 굽은 구간까지 남은 분. 이미 안에 있으면 0, 없으면 null. */
    fun minutesToNextCurvy(): Double? = curvyRangesM.firstOrNull()?.let { max(it.start, 0.0) / metersPerMin }

    companion object {
        /** 막히거나 정차 중이어도 도착 시간이 무한대가 되지 않도록 쓰는 최소 속도. */
        const val MIN_SPEED_KPH = 30.0
        const val CLUSTER_M = 3_000.0
        const val CLUSTER_TURNS = 3

        fun curvyRanges(sorted: List<Double>): List<ClosedFloatingPointRange<Double>> {
            val ranges = mutableListOf<ClosedFloatingPointRange<Double>>()
            for (i in 0..sorted.size - CLUSTER_TURNS) {
                val a = sorted[i]
                val b = sorted[i + CLUSTER_TURNS - 1]
                if (b - a <= CLUSTER_M) {
                    val last = ranges.lastOrNull()
                    if (last != null && a <= last.endInclusive) ranges[ranges.lastIndex] = last.start..max(b, last.endInclusive)
                    else ranges += a..b
                }
            }
            return ranges
        }
    }
}

/** 지금 주행 상황. 미래 계산은 이 값이 그대로 이어진다고 보고, 시계와 운전 시간만 앞으로 넘긴다. */
data class DriveContext(
    val nowHour: Int,
    val nowMinuteOfHour: Int,
    val continuousDrivingMin: Double,
    val drivingChange: Double,
    val cabinTempC: Double?,
    val recirculationMin: Double,
    val speedKph: Double,
    val turnDistancesM: List<Double>,
)

/**
 * 예측: 사람의 통이 언제 찰지 앞으로 1분씩 넘겨 계산한다(상태 예측).
 * 결과는 "여유"(분)이며, 속도 ±20%와 통 크기 ±10%로 범위를 함께 낸다.
 */
object Forecaster {
    const val HORIZON_MIN = 120

    /** 통이 [capacity]에 닿을 때까지 남은 분. [HORIZON_MIN] 안에 안 차면 null. */
    fun minutesUntilFull(p: Passenger, load: Double, capacity: Double, ctx: DriveContext, speedFactor: Double = 1.0): Double? {
        if (load >= capacity) return 0.0
        val route = RouteAhead(ctx.turnDistancesM, ctx.speedKph * speedFactor)
        var l = load
        for (t in 0 until HORIZON_MIN) {
            val rate = rateAt(p, t.toDouble(), ctx, route)
            if (l + rate >= capacity) return t + (capacity - l) / rate
            l += rate
        }
        return null
    }

    /** 지금의 흔들림(가다 서다)은 지표의 창(10분) 동안만 이어진다고 보고 서서히 줄인다. 앞으로의 굽은 길은 회전 안내로 따로 넣는다. */
    private fun changeAt(ctx: DriveContext, t: Double) = ctx.drivingChange * (1.0 - t / CHANGE_FADE_MIN).coerceIn(0.0, 1.0)
    private const val CHANGE_FADE_MIN = 10.0

    fun rateAt(p: Passenger, t: Double, ctx: DriveContext, route: RouteAhead = RouteAhead(ctx.turnDistancesM, ctx.speedKph)): Double =
        when (p.condition) {
            Condition.MOTION_SICKNESS -> ComfortRates.motion(route.curvyFraction(t), changeAt(ctx, t), p.seat.isRear)
            Condition.DROWSINESS -> {
                val hour = ((ctx.nowHour * 60 + ctx.nowMinuteOfHour + t.toInt()) / 60) % 24
                ComfortRates.drowsy(ctx.continuousDrivingMin + t, hour, changeAt(ctx, t), ctx.cabinTempC, ctx.recirculationMin + t)
            }
        }

    data class Margin(val point: Double?, val low: Double?, val high: Double?)

    fun margin(p: Passenger, load: Double, capacity: Double, ctx: DriveContext): Margin = Margin(
        point = minutesUntilFull(p, load, capacity, ctx),
        low = minutesUntilFull(p, load, capacity * 0.9, ctx, speedFactor = 1.2),
        high = minutesUntilFull(p, load, capacity * 1.1, ctx, speedFactor = 0.8),
    )
}
