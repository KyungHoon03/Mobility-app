package app.shimpyo.domain

/** 쉼표가 따로 계산하는 두 가지 부담. 진단이 아니라 "언제 함께 쉬어야 하나"를 계획하기 위한 구분이다. */
enum class Condition { MOTION_SICKNESS, DROWSINESS }

enum class GearState { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

enum class SeatPos {
    DRIVER, FRONT_PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT;

    val isRear: Boolean get() = this == REAR_LEFT || this == REAR_CENTER || this == REAR_RIGHT

    /** 화면과 카드에 쓰는 자리 이름. "뒷좌석"만으로는 누구인지 알 수 없으므로 정확한 자리를 쓴다. */
    val korean: String get() = when (this) {
        DRIVER -> "운전석"
        FRONT_PASSENGER -> "조수석"
        REAR_LEFT -> "뒷좌석 왼쪽"
        REAR_CENTER -> "뒷좌석 가운데"
        REAR_RIGHT -> "뒷좌석 오른쪽"
    }
}

/** 운전자는 졸음, 탑승자분은 멀미를 주로 챙긴다. 차는 누가 앉았는지(나이 등)는 모르고, 앉았는지만 안다. */
enum class Role { DRIVER, PASSENGER }

/** Pleos Vehicle SDK·AAOS 표준 속성에서 읽은 차량 상태 한 장면. 값이 없으면 null. */
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
    /** 차 속도(km/h). AAOS 표준 속성 PERF_VEHICLE_SPEED. */
    val speedKph: Float? = null,
)

data class GeoPoint(val lat: Double, val lon: Double)

data class CarLocation(val point: GeoPoint, val headingDeg: Double)

/** NaviHelper SDK에서 읽은 경로 상황. */
data class RouteContext(
    val location: CarLocation? = null,
    /** 앞으로 남은 회전 안내까지의 거리(m) 목록. */
    val upcomingTurnDistancesM: List<Double> = emptyList(),
)

/** 차에 탄 한 사람. 착석은 차가 알려 주고, 챙길 자리는 출발 전 정차 중에 체크한다. 체크한 자리만 챙긴다. */
data class Passenger(val profileId: String, val role: Role, val seat: SeatPos) {
    val condition: Condition get() = if (role == Role.DRIVER) Condition.DROWSINESS else Condition.MOTION_SICKNESS

    /** 카드에 쓰는 호칭. 사람이 문장의 주인공이 되도록 한다. */
    val label: String get() = when (role) {
        Role.DRIVER -> "운전자님"
        Role.PASSENGER -> "${seat.korean} 탑승자분"
    }

    val key: String get() = "$profileId|${condition.name}"
}

/** 쉼터 주차 때 받는 응답. 쉰 시점이 그 사람의 한계보다 앞이었는지 뒤였는지 알려 준다. */
enum class RestFeedback { ALREADY_UNCOMFORTABLE, JUST_RIGHT, COULD_GO_ON }

/** 학습 기록(성과 지표 계산용). 차 안에만 저장한다. */
data class CareEvent(
    val timeMs: Long,
    val key: String,
    /** "NOW_UNCOMFORTABLE" 또는 RestFeedback 이름. */
    val kind: String,
    /** 그 순간 계산된 통 채움 정도(0..). */
    val load: Double,
)
