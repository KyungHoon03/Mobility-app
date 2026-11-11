package app.shimpyo.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * 조향 방향 전환 횟수와 브레이크를 밟은 횟수로 최근 주행이 얼마나 흔들렸는지 0..1로 나타낸다.
 *
 * 근거: 반복되는 좌우·앞뒤 흔들림이 멀미를 키운다(Souman 외 2026, Henry 외 2023).
 * 다만 이 값은 실제 가속도를 재는 것이 아니라 조향각·브레이크로 만든 대리지표다.
 */
class DrivingChangeIndex(
    private val windowMs: Long = 10 * 60_000L,
    /** 앞바퀴 각도 기준 이만큼 움직여야 방향 전환으로 본다(도). */
    private val reversalThresholdDeg: Float = 1.0f,
) {
    private val reversals = ArrayDeque<Long>()
    private val brakePresses = ArrayDeque<Long>()
    private var anchorAngle: Float? = null
    private var lastDirection = 0
    private var lastBrake = false

    fun onSteering(timeMs: Long, angleDeg: Float) {
        val anchor = anchorAngle
        if (anchor == null) {
            anchorAngle = angleDeg
        } else {
            val delta = angleDeg - anchor
            if (abs(delta) >= reversalThresholdDeg) {
                val direction = if (delta > 0) 1 else -1
                if (lastDirection != 0 && direction != lastDirection) reversals.addLast(timeMs)
                lastDirection = direction
                anchorAngle = angleDeg
            }
        }
        trim(timeMs)
    }

    fun onBrake(timeMs: Long, pressed: Boolean) {
        if (pressed && !lastBrake) brakePresses.addLast(timeMs)
        lastBrake = pressed
        trim(timeMs)
    }

    fun value(nowMs: Long): Double {
        trim(nowMs)
        val minutes = windowMs / 60_000.0
        val reversalsPerMin = reversals.size / minutes
        val brakesPerMin = brakePresses.size / minutes
        val v = 0.6 * min(reversalsPerMin / 6.0, 1.0) + 0.4 * min(brakesPerMin / 2.0, 1.0)
        return v.coerceIn(0.0, 1.0)
    }

    fun reset() {
        reversals.clear(); brakePresses.clear()
        anchorAngle = null; lastDirection = 0; lastBrake = false
    }

    private fun trim(nowMs: Long) {
        val from = nowMs - windowMs
        while (reversals.isNotEmpty() && reversals.first() < from) reversals.removeFirst()
        while (brakePresses.isNotEmpty() && brakePresses.first() < from) brakePresses.removeFirst()
    }
}
