package app.shimpyo.domain

import app.shimpyo.data.DemoRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrivingChangeIndexTest {
    @Test
    fun steadyDrivingIsLowChange() {
        val idx = DrivingChangeIndex()
        for (t in 0 until 600) idx.onSteering(t * 1_000L, 0.2f)
        assertEquals(0.0, idx.value(600_000L), 1e-9)
    }

    @Test
    fun zigzagAndBrakesRaiseChange() {
        val idx = DrivingChangeIndex()
        for (t in 0 until 120) {
            idx.onSteering(t * 5_000L, if (t % 2 == 0) 5f else -5f)
            idx.onBrake(t * 5_000L, t % 4 == 0)
        }
        assertTrue(idx.value(600_000L) > 0.5)
    }
}

class RouteAheadTest {
    @Test
    fun threeTurnsWithin3kmFormACurvyRange() {
        val r = RouteAhead(listOf(9_000.0, 9_700.0, 10_400.0, 11_100.0, 40_000.0), speedKph = 60.0)
        assertEquals(1, r.curvyRangesM.size)
        assertEquals(9_000.0, r.curvyRangesM[0].start, 1e-9)
        // 60km/h = 1km/분 → 9분 뒤 시작
        assertEquals(9.0, r.minutesToNextCurvy()!!, 1e-9)
        assertTrue(r.isCurvyAt(10.0))
        assertFalse(r.isCurvyAt(5.0))
    }

    @Test
    fun scatteredTurnsAreNotCurvy() {
        val r = RouteAhead(listOf(1_000.0, 5_000.0, 9_000.0), speedKph = 90.0)
        assertTrue(r.curvyRangesM.isEmpty())
        assertNull(r.minutesToNextCurvy())
    }
}

class ForecasterTest {
    private val child = Passenger("child", Role.CHILD, SeatPos.REAR_LEFT)
    private val driver = Passenger("driver", Role.DRIVER, SeatPos.DRIVER)
    private fun ctx(turns: List<Double> = emptyList(), hour: Int = 11, cont: Double = 0.0) =
        DriveContext(hour, 0, cont, 0.0, 22.0, 0.0, 60.0, turns)

    @Test
    fun curvyRoadAheadShortensChildMargin() {
        val straight = Forecaster.minutesUntilFull(child, 0.3, 0.6, ctx())!!
        val curvy = Forecaster.minutesUntilFull(child, 0.3, 0.6, ctx(turns = listOf(5_000.0, 5_700.0, 6_400.0, 7_100.0, 7_800.0)))!!
        assertTrue("curvy=$curvy straight=$straight", curvy < straight)
        assertTrue(curvy > 5.0) // 굽은 길 시작(5분 뒤) 전에는 차지 않는다
    }

    @Test
    fun afternoonDipShortensDriverMargin() {
        val morning = Forecaster.minutesUntilFull(driver, 0.2, 0.7, ctx(hour = 10, cont = 30.0))!!
        val afternoon = Forecaster.minutesUntilFull(driver, 0.2, 0.7, ctx(hour = 14, cont = 30.0))!!
        assertTrue(afternoon < morning)
    }

    @Test
    fun rangeBracketsPoint() {
        val m = Forecaster.margin(child, 0.3, 0.6, ctx(turns = listOf(5_000.0, 5_700.0, 6_400.0)))
        assertTrue("$m", m.low!! <= m.point!! && m.point!! <= m.high!!)
    }
}

class CalibrationTest {
    @Test
    fun narrowsTowardTheRightPoint() {
        var c = Calibration()                       // 0.5..1.0
        c = c.onRestFeedback(0.7, RestFeedback.ALREADY_UNCOMFORTABLE)
        assertEquals(0.5, c.lo, 1e-9); assertEquals(0.7, c.hi, 1e-9)
        c = c.onRestFeedback(0.55, RestFeedback.JUST_RIGHT)
        assertTrue("$c", c.lo >= 0.55 && c.hi < 0.7 && c.hi > c.lo)
        assertEquals(2, c.answers)
    }

    @Test
    fun couldGoOnRaisesLowerBound() {
        val c = Calibration().onRestFeedback(0.8, RestFeedback.COULD_GO_ON)
        assertEquals(0.8, c.lo, 1e-9)
        assertTrue(c.hi > c.lo)
    }

    @Test
    fun nowUncomfortableCapsUpperBound() {
        val c = Calibration().onNowUncomfortable(0.55)
        assertEquals(0.55, c.hi, 1e-9)
        assertTrue(c.lo < c.hi)
    }
}

class RestOpportunityTest {
    private val here = CarLocation(GeoPoint(37.0, 127.0), headingDeg = 0.0)
    private fun stop(name: String, km: Double, kind: RestStop.Kind = RestStop.Kind.SERVICE_AREA) =
        RestStop(name, GeoPoint(37.0 + km / 111.0, 127.0), kind)

    @Test
    fun picksTheLastStopBeforeDeadline() {
        // 60km/h, 도로 계수 1.2 → 10km = 12분
        val ahead = RestOpportunity.stopsAhead(listOf(stop("가", 5.0), stop("나", 10.0), stop("다", 30.0)), here, 60.0)
        val c = RestOpportunity.choose(ahead, deadlineMin = 20.0, preferServiceArea = false)!!
        assertEquals("나", c.stop.stop.name)
        assertTrue(c.isLastBeforeDeadline)
        assertFalse(c.late)
    }

    @Test
    fun childPrefersServiceArea() {
        val ahead = RestOpportunity.stopsAhead(listOf(stop("휴게소", 5.0), stop("쉼터", 10.0, RestStop.Kind.REST_AREA)), here, 60.0)
        assertEquals("휴게소", RestOpportunity.choose(ahead, 20.0, preferServiceArea = true)!!.stop.stop.name)
        assertEquals("쉼터", RestOpportunity.choose(ahead, 20.0, preferServiceArea = false)!!.stop.stop.name)
    }

    @Test
    fun nothingReachableFallsBackToNearestAsLate() {
        val ahead = RestOpportunity.stopsAhead(listOf(stop("먼곳", 30.0)), here, 60.0)
        val c = RestOpportunity.choose(ahead, 5.0, false)!!
        assertTrue(c.late)
    }

    @Test
    fun oppositeSideServiceAreaIsSkipped() {
        assertTrue(RestOpportunity.isOppositeSide("입장거봉 포도 휴게소 (서울 방향)", 180.0))
        assertFalse(RestOpportunity.isOppositeSide("천안삼거리휴게소(부산방향)", 180.0))
        assertFalse(RestOpportunity.isOppositeSide("안성휴게소", 180.0))
        assertTrue(RestOpportunity.isOppositeSide("망향휴게소(부산방향)", 0.0))
    }

    @Test
    fun stopsBehindAreIgnored() {
        val ahead = RestOpportunity.stopsAhead(listOf(stop("뒤", -10.0)), here, 60.0)
        assertTrue(ahead.isEmpty())
    }
}

class CareTextTest {
    @Test
    fun particlesFollowTheLastSyllable() {
        val child = Passenger("child", Role.CHILD, SeatPos.REAR_LEFT)
        val driver = Passenger("driver", Role.DRIVER, SeatPos.DRIVER)
        assertEquals("뒷좌석 아이가", CareText.subject(child))
        assertEquals("운전자님이", CareText.subject(driver))
        assertEquals("운전자님은", CareText.topic(driver))
    }
}

/** 시연 시나리오를 처음부터 끝까지 돌려, 케어가 의도한 순서로 나오는지 확인한다. */
class DemoScenarioTest {
    private fun loadStops(): List<RestStop> {
        val f = listOf(File("src/main/assets/rest_stops.json"), File("app/src/main/assets/rest_stops.json")).first { it.exists() }
        val re = Regex("""\{"n":\s*"([^"]+)",\s*"lat":\s*([\d.]+),\s*"lon":\s*([\d.]+),\s*"k":\s*"([SR])"\}""")
        return re.findAll(f.readText()).map {
            RestStop(it.groupValues[1], GeoPoint(it.groupValues[2].toDouble(), it.groupValues[3].toDouble()),
                if (it.groupValues[4] == "S") RestStop.Kind.SERVICE_AREA else RestStop.Kind.REST_AREA)
        }.toList()
    }

    @Test
    fun familyTripThenAfternoonDrive() {
        val stops = loadStops()
        assertTrue("stops=${stops.size}", stops.size > 500)
        val route = DemoRoute()
        var nowMs = 13 * 3_600_000L
        val planner = CarePlanner({ nowMs }, { ms -> ((ms / 3_600_000L) % 24).toInt() to ((ms / 60_000L) % 60).toInt() })
        val child = Passenger("child", Role.CHILD, SeatPos.REAR_LEFT)
        val driver = Passenger("driver", Role.DRIVER, SeatPos.DRIVER)
        val people = listOf(driver, child)
        val cal = mutableMapOf(child.key to Calibration(0.5, 0.7, 2), driver.key to Calibration(0.5, 0.8, 1))
        route.depart()
        val log = StringBuilder()
        val stages = mutableListOf<Pair<Int, CareStage>>()
        var cards = 0
        while (!route.finished && route.minute < 240) {
            val s = VehicleSnapshot(gear = route.gear, occupiedSeats = setOf(SeatPos.DRIVER, SeatPos.REAR_LEFT), cabinTempC = 25.5f,
                recirculationOn = true, speedKph = route.speedKph.toFloat())
            planner.onVehicle(s)
            val plan = planner.tick(s, RouteContext(route.location(), route.turnsAheadM()), people, cal, stops)
            stages += route.minute to plan.stage
            log.append("${route.minute} ${route.gear} km=${"%.1f".format(route.km)} ${plan.stage} focus=${plan.focus?.passenger?.label} ")
                .append(plan.people.joinToString { "${it.passenger.profileId}:${"%.2f".format(it.load)}/${"%.2f".format(it.capacity)} m=${it.margin.point?.let { m -> "%.0f".format(m) }}" })
                .append(" choice=${plan.choice?.stop?.stop?.name}@${plan.choice?.stop?.etaMin?.let { "%.0f".format(it) }}\n")
            if (plan.stage == CareStage.CARE) {
                cards++
                log.append("   CARD: ${CareText.cardTitle(plan)} / ${CareText.cardReason(plan)} / ${CareText.cardOpportunity(plan)} / ${CareText.companionLine(plan)}\n")
                planner.accept(plan)
                route.waypoint(plan.choice!!.stop.stop)
            }
            planner.arrivalQuestion?.let { q ->
                log.append("   ASK: ${CareText.arrivalQuestion(q)} load=${"%.2f".format(q.load)}\n")
                cal[q.person.key] = cal.getValue(q.person.key).onRestFeedback(q.load, RestFeedback.JUST_RIGHT)
                planner.answered()
            }
            route.advance()
            nowMs += 60_000L
        }
        println(log)
        assertTrue("finished", route.finished)
        assertEquals(2, cards)
        val firstHeadsUp = stages.first { it.second == CareStage.HEADS_UP }.first
        val firstCare = stages.first { it.second == CareStage.CARE }.first
        assertTrue("heads-up before care: $firstHeadsUp < $firstCare", firstHeadsUp < firstCare)
    }
}
