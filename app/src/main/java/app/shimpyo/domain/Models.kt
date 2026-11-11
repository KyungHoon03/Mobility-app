package app.shimpyo.domain

/** 앱이 다루는 두 가지 상태. 진단이 아니라 "쉬어 갈 때"를 계획하기 위한 구분이다. */
enum class Condition { MOTION_SICKNESS, DROWSINESS }

enum class GearState { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

enum class SeatPos { DRIVER, FRONT_PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT }

/** Pleos Vehicle SDK에서 읽은 차량 상태 한 장면. 값이 없으면 null. */
data class VehicleSnapshot(
    /** 앞바퀴 조향각(도). 왼쪽이 음수. 핸들 회전각이 아니다. */
    val steeringAngleDeg: Float? = null,
    val brakePressed: Boolean? = null,
    val gear: GearState = GearState.UNKNOWN,
    val occupiedSeats: Set<SeatPos> = emptySet(),
    val cabinTempC: Float? = null,
    val recirculationOn: Boolean? = null,
    /** 이번 주행 시간(분). */
    val tripDrivingMinutes: Int? = null,
)

data class GeoPoint(val lat: Double, val lon: Double)

data class CarLocation(val point: GeoPoint, val headingDeg: Double)

/** NaviHelper SDK에서 읽은 경로 상황. */
data class RouteContext(
    val location: CarLocation? = null,
    /** 앞으로 남은 회전 안내까지의 거리(m) 목록. */
    val upcomingTurnDistancesM: List<Double> = emptyList(),
)

/** 점수 계산에 쓰는 특징값. 모두 차량·내비 데이터에서 만든 "대리지표"이며 생체 측정값이 아니다. */
data class Features(
    /** 조향 방향 전환과 브레이크 빈도로 만든 주행 변화 지표 0..1 (실제 가속도 측정 아님). */
    val drivingChange: Double,
    /** 앞으로 3km 안의 회전 안내 밀도 0..1. */
    val curveAhead: Double,
    val minutesSinceStart: Double,
    val hourOfDay: Int,
    val cabinTempC: Double?,
    val recirculationMinutes: Double,
)

/** 탑승자가 직접 남긴 신호(멀미 버튼, 주차 후 졸음 응답). 개인화의 정답 데이터. */
data class Report(
    val timeMs: Long,
    val condition: Condition,
    val profileId: String,
    /** 신호를 남긴 순간의 기본 규칙 점수. */
    val score: Double,
    val minutesSinceStart: Double,
)

data class Profile(val id: String, val name: String)
