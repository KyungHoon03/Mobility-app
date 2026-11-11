package app.shimpyo.domain

import kotlin.math.max
import kotlin.math.min

/**
 * 학습: 사람이 정확히 언제 힘들어지는지는 끝까지 알 수 없다.
 * 대신 쉴 때마다 "빨랐다·늦었다"를 확인해 그 사람의 통 크기 범위 [lo, hi]를 좁힌다.
 * 처음에는 0.5~1.0 사이 어딘가로 두고, 계획은 범위의 가운데([estimate])를 기준으로 세운다.
 */
data class Calibration(val lo: Double = 0.5, val hi: Double = 1.0, val answers: Int = 0) {
    val estimate: Double get() = (lo + hi) / 2

    /** 주행 중 "지금 불편해요": 우리가 늦었고, 한계는 지금 채움 정도보다 아래다. */
    fun onNowUncomfortable(load: Double): Calibration = below(load).copy(answers = answers + 1)

    /** 쉼터 주차 때 응답. */
    fun onRestFeedback(load: Double, feedback: RestFeedback): Calibration = when (feedback) {
        RestFeedback.ALREADY_UNCOMFORTABLE -> below(load)
        RestFeedback.COULD_GO_ON -> above(load)
        RestFeedback.JUST_RIGHT -> {
            // 쉰 지점까지는 편안했고, 계획한 한계도 크게 틀리지 않았다: 아래는 쉰 지점으로 올리고 위는 가운데 쪽으로 당긴다.
            val newLo = max(lo, min(load, estimate))
            val newHi = max(newLo * 1.05, hi - (hi - estimate) * 0.5)
            copy(lo = newLo, hi = newHi)
        }
    }.copy(answers = answers + 1)

    private fun below(load: Double): Calibration {
        // 한 번의 신호로 범위가 무너지지 않도록, 위 한계는 한 번에 최대 30%까지만 내린다.
        val newHi = min(hi, maxOf(load, hi * MAX_STEP_DOWN, FLOOR))
        val newLo = if (lo < newHi) lo else newHi * 0.8
        return copy(lo = max(newLo, FLOOR * 0.8), hi = newHi)
    }

    private fun above(load: Double): Calibration {
        val newLo = max(lo, load)
        val newHi = if (hi > newLo) hi else min(newLo * 1.25, CEILING)
        return copy(lo = newLo, hi = newHi)
    }

    companion object {
        const val FLOOR = 0.2
        const val CEILING = 2.0
        const val MAX_STEP_DOWN = 0.7
    }
}
