# 쉼표 (Shimpyo)

**모든 탑승자를 챙기는 차.** 차에 탄 모든 사람을 지켜보다가, 누군가 힘들어지기 전에 차 전체가 함께 쉬도록 "마지막 편안한 휴식 기회"를 찾아 챙겨 주는 Pleos Connect 앱입니다. 판단은 쉼표가, 실행은 운전자의 버튼 한 번이, 길 안내는 내비가 맡습니다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 구조: 사람 → 판단 → 케어 → 내비
1. **탑승자:** 차는 자리마다 사람이 앉았는지만 압니다. 출발 전 정차 중에 챙길 자리를 체크하면(조수석, 뒷좌석 왼쪽·가운데·오른쪽) 체크한 자리의 탑승자분만 챙깁니다. 운전자님은 항상 챙깁니다. 호칭은 "뒷좌석 오른쪽 탑승자분"처럼 정확한 자리로 부르고, 기록도 자리별로 쌓습니다.
2. **사람별 여유 예측(`Forecaster`):** 사람마다 "편안함 통"에 부담이 쌓이는 속도를 앞으로 1분씩 넘겨 계산해, 통이 언제 찰지(여유)를 구합니다. 속도 ±20%, 통 크기 ±10%로 범위도 냅니다.
   - 멀미(주로 동승자): 앞으로의 굽은 길(TBT 3km 안 3개 이상 ÷ 차 속도), 가다 서다(조향·브레이크 대리지표), 뒷좌석
   - 졸음(주로 운전자): 연속 운전 시간, 다가오는 시간대(새벽 2~6시·오후 2~4시), 단조로운 길, 약간 따뜻한 실내, 내기순환
   - 부담 속도의 방향은 연구 근거, 숫자(분당 몇 %)는 **가정값**입니다(`ComfortRates`).
3. **휴식 기회 예측(`RestOpportunity`):** 진행 방향 앞쪽 쉼터의 도착 예상 시간(직선거리 × 1.2 ÷ 속도)을 구하고, 가장 먼저 힘들어질 사람의 여유 안에서 가장 멀리 있는 곳 = **마지막 편안한 휴식 기회**를 고릅니다. 탑승자분이 기준이면 화장실·매점이 있는 휴게소를 우선하고, 굽은 길이 기한 안에 시작되면 굽은 길 **전에** 쉬도록 고릅니다. 이름에 "○○방향"이 있는 반대편 휴게소는 거릅니다(어림 규칙).
4. **케어(`CarePlanner`, `CareText`):** 지켜봄(상태 한 줄) → 미리 알림(가장 짧은 여유 30분 안) → 챙김 카드(기회가 15분 안). 카드 문장은 사람 → 이유 → 기회 → 행동 하나. 예: "뒷좌석 왼쪽 탑승자분이 곧 힘들어질 수 있어요 / 약 20분 뒤 굽은 길이 이어져요 / 약 15분 뒤 닿는 ○○휴게소가 굽은 길 전 마지막 휴식 기회예요 / [○○휴게소에서 함께 쉬기]". 두 사람이 겹치면 카드는 하나만, 둘째 사람은 "운전자님도 함께 쉬면 좋아요".
5. **내비:** 버튼 한 번에 `NaviHelper.addWaypoint`로 경유지를 넘깁니다.

## 학습(`Calibration`): 정확한 값 대신 범위를 좁힌다
- 사람이 정확히 언제 힘들어지는지는 알 수 없으므로, 통 크기를 범위 [lo, hi](처음 0.5~1.0)로 두고 가운데 값으로 계획합니다.
- **"지금 불편해요"**(동승자가 주행 중 누름, 누가 불편한지 고름): 우리가 늦었다는 정답 → 위 한계를 그 순간 채움 정도로 내림, 계획을 바로 앞당김
- **쉼터 주차 때 한 번:** "이미 불편했어요 / 딱 좋았어요 / 더 갈 수 있었어요" → 위·아래 한계 조정
- 정확도는 "딱 좋았어요" 비율과 예측보다 먼저 눌린 "지금 불편해요"로 잽니다(기록 `CareEvent`).

## 하지 않는 것
진단하지 않습니다(판단은 하되 점수·경고가 아닌 챙기는 말로). 차량을 제어하지 않습니다. 주행 중에는 카드 1장과 버튼 1개만. 기록은 차 안(앱 내부 저장소)에만 저장합니다.

## 판단에 쓰는 데이터와 근거
| 연구 근거 | 차량 데이터 (API) |
|---|---|
| 반복되는 좌우·앞뒤 흔들림이 멀미를 키운다 (Souman 외 2026, Henry 외 2023) | 앞바퀴 조향각 `SteeringWheel.registerSteeringWheelAngle`, 브레이크 `Brake.registerBrakePedalStatus` → 주행 변화 대리지표(실제 가속도 측정 아님) |
| 굽은 길·회전에서 멀미가 잦다 (Schmidt 외 2020, Metzulat 외 2025) | 내비 회전 안내 `NaviHelper.getTBTInfo` + 차 속도 `VehiclePropertyIds.PERF_VEHICLE_SPEED`(AAOS 표준) → 몇 분 뒤 굽은 길 |
| 뒷좌석에서 멀미가 가장 잦다 | 좌석별 착석 `Seat.registerSeatOccupancy` |
| 연속 운전 시간·시간대·단조로운 주행이 졸음을 키운다 (Horne·Reyner 1995·1999, Zeller 외 2020) | 기어 `VehiclePropertyIds.GEAR_SELECTION`(AAOS 표준), 이번 주행 시간 `Odometer.registerTripCurrentDrivingTime`, 현재 시각 |
| 중립보다 약간 따뜻할 때 가장 졸렸다 (Sunagawa 외 2023) | 실내 온도 `HVAC.registerHvacTemperatureCurrent` |
| 내기순환이 차 안 CO2를 쌓는다(보조 신호) (Hudda·Fruin 2017) | 내기순환 `HVAC.registerHvacRecirculationState` |
| 졸음에는 멈추고 쉬기, 15분 미만 쪽잠·커피 / 멀미에는 멈추기·앞좌석·창문 (Horne·Reyner 1996·1997·1999, Metzulat 외 2025) | 경유지 추가 `NaviHelper.addWaypoint`, 현재 위치 `NaviHelper.getCurrentLocationInfo`, 쉼터에서 할 일·15분 쪽잠 알람 |

## 기술 스택
- Kotlin 2.0, Jetpack Compose(Material 3), Coroutines/Flow
- Android Gradle Plugin 8.8.2, Gradle 8.10.2, compileSdk/targetSdk 35, **minSdk 28**(Pleos 제품 앱 요건), JDK 17 이상
- Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5 (`https://nexus-playground.pleos.ai/repository/maven-releases/`)
- AAOS `android.car`: `CarUxRestrictionsManager`(주행 제한 상태), `CarPropertyManager`(기어·차 속도)
- 서버 없음. 기록은 앱 내부 JSON 파일

## 구조
```
app/src/main/java/app/shimpyo/
├── domain/   순수 Kotlin 판단 로직(단위 테스트 대상)
│   ├── Models.kt              탑승자(Passenger)·차량 상태·응답
│   ├── DrivingChangeIndex.kt  조향 방향 전환·브레이크 빈도 → 주행 변화 지표
│   ├── Comfort.kt             편안함 통 부담 속도(가정값)·앞으로의 굽은 길·여유 예측
│   ├── RestOpportunity.kt     쉼터 도착 예상 시간·마지막 편안한 휴식 기회
│   ├── Calibration.kt         쉼터 응답으로 사람별 통 크기 범위 좁히기
│   ├── CarePlanner.kt         통 채우기·비우기, 케어 단계, 주차 시 질문
│   ├── CareText.kt            케어 문장(사람→이유→기회→행동), 쉼터에서 할 일
│   └── RestStops.kt           거리·방위 계산
├── data/     Pleos SDK·AAOS 연동
│   ├── PleosVehicleSource.kt  Vehicle SDK 읽기 구독
│   ├── AaosPropertyWatcher.kt GEAR_SELECTION·PERF_VEHICLE_SPEED 표준 속성
│   ├── PleosNaviGateway.kt    NaviHelper 위치·TBT·경유지 추가
│   ├── UxRestrictionWatcher.kt 주행 제한 상태
│   ├── RestStopRepository.kt  쉼터 좌표(assets/rest_stops.json)
│   ├── RecordStore.kt         기록·설정 저장
│   └── DemoSources.kt         시연 모드(DemoRoute 가상 경로 재생)
├── ui/       주행 화면 / 정차 화면
└── MainViewModel.kt
```

## 실행 방법
1. Android Studio(또는 JDK 17+와 Android SDK)를 준비합니다.
2. Pleos Connect Emulator를 설치합니다(공식 가이드: Pleos Connect 개발 환경 설정). SDK Manager → SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` 추가 → API 34 Pleos Connect system image 설치 → Automotive / Pleos Connect v2.1.0 AVD 생성.
3. 빌드·설치:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```
4. 차량 상태 조작: Pleos Connect Virtual Console을 엽니다.
   ```bash
   adb forward tcp:8088 tcp:8088
   ```
   브라우저에서 `http://127.0.0.1:8088/` → Emulation Controls에서 기어 D, 브레이크, 조향, 공조를 바꿉니다.
5. 차량 값을 바꾸기 어려울 때는 정차 화면의 **시연 모드**로 가상 주행을 1초 = 1분으로 재생합니다(화면에 "시연 모드 · 가상 주행 데이터"로 표시). 오후 1시 출발 → 굽은 길 전 뒷좌석 왼쪽 탑승자분 기준 챙김 카드 → 휴게소 주차·질문 → 오후 2시 무렵 운전자 기준 카드 → 졸음쉼터 주차·15분 쪽잠 알람. 시연 모드의 학습 기록은 실제 기록과 따로 둡니다.

## 테스트 환경
- Pleos Connect Emulator **v2.1.0**(Android 14, API 34, x86_64), 화면 **2560×1440**, 앱 창 1682×1210(멀티 윈도우)
- 확인한 것(2026-11-11): 차량 상태 구독(조향·브레이크·좌석 4곳·실내 온도·내기순환), GEAR_SELECTION 수신, 기어 D 전환 시 주행 화면 자동 전환, "멀미나요" → 실제 내비 위치 기준 쉼터 제안 → `addWaypoint` 요청이 Pleos 내비 앱에 전달됨
- 에뮬레이터에서 이번 주행 시간(Odometer)은 값이 오지 않아 앱 자체 타이머로 대신합니다.
- 시연 영상(2026-11-11): 실제 연동 장면 36초 + 시연 모드 시나리오 100초 + 15분 쪽잠 알람 10초
- 단위 테스트 17개(시연 시나리오 전체 재생 포함): `./gradlew testDebugUnitTest`

## APK
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`(공모전용, 디버그 키 서명)
- 제출 시 `apk/팀명_앱이름_release.apk`로 저장소에 올립니다.

## 데이터와 라이선스
- 쉼터 좌표(`app/src/main/assets/rest_stops.json`): © OpenStreetMap contributors, **ODbL 1.0** (https://www.openstreetmap.org/copyright). 2026-11-11 Overpass API로 `highway=services|rest_area` 중 이름에 "휴게소·쉼터"가 들어간 1,011곳을 추렸습니다. 이 파일은 ODbL을 따릅니다.
- Pleos SDK와 에뮬레이터는 42dot의 Pleos Connect SDK 라이선스를 따르며, 이 저장소에는 SDK 파일을 넣지 않습니다(빌드 때 Maven으로 받음).
