package app.shimpyo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingChangeIndexTest {
    @Test
    fun straightRoadIsCalm() {
        val idx = DrivingChangeIndex()
        for (i in 0 until 600) idx.onSteering(i * 1_000L, 0.2f * (i % 2))
        assertEquals(0.0, idx.value(600_000L), 1e-9)
    }

    @Test
    fun weavingAndBrakingRaisesIndex() {
        val idx = DrivingChangeIndex()
        for (i in 0 until 600) {
            val t = i * 1_000L
            idx.onSteering(t, if (i % 2 == 0) 4f else -4f)
            idx.onBrake(t, i % 10 == 0)
        }
        assertTrue(idx.value(600_000L) > 0.8)
    }

    @Test
    fun oldEventsLeaveTheWindow() {
        val idx = DrivingChangeIndex(windowMs = 60_000L)
        for (i in 0 until 60) idx.onSteering(i * 1_000L, if (i % 2 == 0) 4f else -4f)
        assertTrue(idx.value(60_000L) > 0.0)
        assertEquals(0.0, idx.value(10 * 60_000L), 1e-9)
    }
}

class RiskModelTest {
    private fun f(change: Double = 0.0, curve: Double = 0.0, min: Double = 0.0, hour: Int = 11, temp: Double? = null, recirc: Double = 0.0) =
        Features(change, curve, min, hour, temp, recirc)

    @Test
    fun motionSicknessRisesWithCurvesAndShaking() {
        assertTrue(RiskModel.motionSickness(f(change = 0.9, curve = 1.0, min = 40.0)) > RiskModel.motionSickness(f(min = 40.0)))
    }

    @Test
    fun drowsinessRisesWithTimeNightMonotonyWarmth() {
        val low = RiskModel.drowsiness(f(change = 0.8, min = 10.0, hour = 11, temp = 19.0))
        val high = RiskModel.drowsiness(f(change = 0.0, min = 130.0, hour = 3, temp = 25.0, recirc = 70.0))
        assertTrue(high > 0.9)
        assertTrue(low < 0.3)
    }

    @Test
    fun slightlyWarmIsInvertedU() {
        assertEquals(0.0, RiskModel.slightlyWarm(18.0), 1e-9)
        assertEquals(1.0, RiskModel.slightlyWarm(25.0), 1e-9)
        assertEquals(0.0, RiskModel.slightlyWarm(31.0), 1e-9)
    }

    @Test
    fun curveAheadCountsTurnsWithin3km() {
        assertEquals(1.0, RiskModel.curveAhead(listOf(100.0, 500.0, 900.0, 2000.0, 9000.0)), 1e-9)
        assertEquals(0.0, RiskModel.curveAhead(listOf(5000.0)), 1e-9)
    }
}

class PersonalizerTest {
    private val p = Personalizer(defaultThreshold = 0.6)
    private fun r(score: Double, profile: String = "p1", c: Condition = Condition.MOTION_SICKNESS) = Report(0, c, profile, score, 30.0)

    @Test
    fun usesDefaultUntilThreeReports() {
        assertEquals(0.6, p.threshold(listOf(r(0.4), r(0.4)), Condition.MOTION_SICKNESS, "p1"), 1e-9)
    }

    @Test
    fun lowersThresholdBelowReportedScores() {
        val t = p.threshold(listOf(r(0.4), r(0.5), r(0.45)), Condition.MOTION_SICKNESS, "p1")
        assertEquals(0.45 * 0.85, t, 1e-9)
    }

    @Test
    fun keepsPeopleAndConditionsSeparate() {
        val reports = listOf(r(0.3), r(0.3), r(0.3), r(0.3, profile = "p2"))
        assertEquals(0.6, p.threshold(reports, Condition.MOTION_SICKNESS, "p2"), 1e-9)
        assertEquals(0.6, p.threshold(reports, Condition.DROWSINESS, "p1"), 1e-9)
    }
}

class RestStopSelectorTest {
    private val here = CarLocation(GeoPoint(37.30, 127.05), headingDeg = 180.0) // 남쪽으로 주행
    private val south = RestStop("남쪽 휴게소", GeoPoint(37.20, 127.05), RestStop.Kind.SERVICE_AREA)
    private val north = RestStop("북쪽 휴게소", GeoPoint(37.36, 127.05), RestStop.Kind.SERVICE_AREA)
    private val farSouth = RestStop("먼 휴게소", GeoPoint(36.90, 127.05), RestStop.Kind.SERVICE_AREA)

    @Test
    fun picksNearestAheadNotBehind() {
        val pick = RestStopSelector.nextAhead(listOf(north, farSouth, south), here)
        assertNotNull(pick)
        assertEquals("남쪽 휴게소", pick!!.stop.name)
        assertEquals(11.1, pick.distanceKm, 0.3)
    }

    @Test
    fun nothingWhenAllBehind() {
        assertNull(RestStopSelector.nextAhead(listOf(north), here))
    }
}

class RestPlannerTest {
    private var now = 0L
    private val planner = RestPlanner(clock = { now }, hourOf = { 3 })

    @Test
    fun suggestsOnlyWhileDrivingAndRespectsCooldown() {
        val parked = VehicleSnapshot(gear = GearState.PARK)
        planner.onVehicle(parked)
        assertNull(planner.evaluate(parked, RouteContext(), emptyList(), "driver", null, emptyList(), true))

        val driving = VehicleSnapshot(gear = GearState.DRIVE, cabinTempC = 25f, tripDrivingMinutes = 130)
        planner.onVehicle(driving)
        now += 1_000
        val first = planner.evaluate(driving, RouteContext(), emptyList(), "driver", null, emptyList(), true)
        assertEquals(Condition.DROWSINESS, first?.condition)
        now += 60_000
        assertNull(planner.evaluate(driving, RouteContext(), emptyList(), "driver", null, emptyList(), true))
    }

    @Test
    fun asksDrowsinessOnlyAfterParkingFromLongDrive() {
        planner.onVehicle(VehicleSnapshot(gear = GearState.DRIVE))
        now += 20 * 60_000L
        planner.onVehicle(VehicleSnapshot(gear = GearState.PARK))
        assertTrue(planner.pendingDrowsinessQuestion)
        planner.answeredDrowsinessQuestion()
        assertTrue(!planner.pendingDrowsinessQuestion)
    }

    @Test
    fun caffeineFreeAdviceHasNoCoffee() {
        val advice = Advice.forCondition(Condition.DROWSINESS, caffeineOk = false)
        assertTrue(advice.none { it.contains("커피를 마신") })
        assertTrue(advice.any { it.contains("쪽잠") })
    }
}
