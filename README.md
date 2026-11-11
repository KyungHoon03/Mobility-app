# 쉼표 (Shimpyo)

**쉬어야 할 때를 먼저 아는 차.** 차가 읽는 흔들림·좌석·경로·운전 시간 데이터와 탑승자의 짧은 신호를 함께 배워서, 멀미나 졸음으로 힘들어지기 전에 쉬어 갈 곳과 그곳에서 할 일을 내비 경유지로 먼저 제안하는 Pleos Connect 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작입니다.

## 무엇을 하나
- **주행 중 화면:** 카드 1장과 큰 버튼 2개(멀미나요 / 운전자가 졸려 보여요)만 보여 줍니다. 버튼은 동승자가 누릅니다.
- **사전 제안:** 점수가 그 사람의 기준점을 넘으면 진행 방향 앞쪽의 가장 가까운 휴게소·졸음쉼터를 찾아 "○km 뒤 ○○에서 쉬어 가요" 카드를 띄우고, 한 번 누르면 Pleos 내비에 경유지로 추가합니다.
- **쉼터에서 할 일:** 졸음에는 15분 이내 쪽잠과 커피(카페인이 맞지 않으면 쪽잠과 무카페인 보조 수단), 멀미에는 하던 일 멈추기·앞좌석·창문 열기를 안내합니다. 에너지음료·단 음료는 권하지 않습니다.
- **쉼터에서 15분 쪽잠 알람:** 받아들인 쉼터에 주차하면 할 일과 15분 알람을 보여 줍니다.
- **주차 후 한 번 묻기:** 15분 이상 운전하고 기어가 P로 바뀌면 "아까 졸렸나요?"를 묻습니다. 주행 중이나 신호 대기 중에는 묻지 않습니다.
- **개인화:** 같은 사람의 기록이 3회 이상 쌓이면, 힘들다고 알린 순간 점수의 평균보다 조금 낮은 지점을 새 기준점으로 삼아 더 일찍 제안합니다.
- **하지 않는 것:** 진단하지 않고, 차량을 제어하지 않으며, 기록은 차 안(앱 내부 저장소)에만 저장합니다.

## 판단에 쓰는 데이터와 근거
| 연구 근거 | 차량 데이터 (API) |
|---|---|
| 반복되는 좌우·앞뒤 흔들림이 멀미를 키운다 (Souman 외 2026, Henry 외 2023) | 앞바퀴 조향각 `SteeringWheel.registerSteeringWheelAngle`, 브레이크 `Brake.registerBrakePedalStatus` → 주행 변화 대리지표(실제 가속도 측정 아님) |
| 굽은 길·회전에서 멀미가 잦다 (Schmidt 외 2020, Metzulat 외 2025) | 내비 회전 안내 `NaviHelper.getTBTInfo` → 앞 3km 회전 밀도 |
| 뒷좌석에서 멀미가 가장 잦다 | 좌석별 착석 `Seat.registerSeatOccupancy` |
| 연속 운전 시간·시간대·단조로운 주행이 졸음을 키운다 (Horne·Reyner 1995·1999, Zeller 외 2020) | 기어 `VehiclePropertyIds.GEAR_SELECTION`(AAOS 표준), 이번 주행 시간 `Odometer.registerTripCurrentDrivingTime`, 현재 시각 |
| 중립보다 약간 따뜻할 때 가장 졸렸다 (Sunagawa 외 2023) | 실내 온도 `HVAC.registerHvacTemperatureCurrent` |
| 내기순환이 차 안 CO2를 쌓는다(보조 신호) (Hudda·Fruin 2017) | 내기순환 `HVAC.registerHvacRecirculationState` |
| 졸음에는 멈추고 쉬기, 15분 미만 쪽잠·커피 (Horne·Reyner 1996·1997·1999) | 경유지 추가 `NaviHelper.addWaypoint`, 현재 위치 `NaviHelper.getCurrentLocationInfo` |

점수 규칙은 `app/src/main/java/app/shimpyo/domain/RiskModel.kt`에 근거와 함께 적어 두었습니다.

## 기술 스택
- Kotlin 2.0, Jetpack Compose(Material 3), Coroutines/Flow
- Android Gradle Plugin 8.8.2, Gradle 8.10.2, compileSdk/targetSdk 35, **minSdk 28**(Pleos 제품 앱 요건), JDK 17 이상
- Pleos Vehicle SDK 2.3.2, Pleos NaviHelper SDK 3.0.5 (`https://nexus-playground.pleos.ai/repository/maven-releases/`)
- AAOS `android.car`: `CarUxRestrictionsManager`(주행 제한 상태), `CarPropertyManager`(기어)
- 서버 없음. 기록은 앱 내부 JSON 파일

## 구조
```
app/src/main/java/app/shimpyo/
├── domain/   순수 Kotlin 판단 로직(단위 테스트 대상)
│   ├── DrivingChangeIndex.kt  조향 방향 전환·브레이크 빈도 → 주행 변화 지표
│   ├── RiskModel.kt           멀미·졸음 기본 규칙 점수, Personalizer(개인 기준점)
│   ├── RestPlanner.kt         주행 시작·주차 추적, 제안 판단(쿨다운 20분)
│   ├── RestStops.kt           진행 방향 앞쪽 쉼터 선택
│   └── Advice.kt              쉼터에서 할 일 문구
├── data/     Pleos SDK·AAOS 연동
│   ├── PleosVehicleSource.kt  Vehicle SDK 읽기 구독
│   ├── AaosGearWatcher.kt     GEAR_SELECTION 표준 속성
│   ├── PleosNaviGateway.kt    NaviHelper 위치·TBT·경유지 추가
│   ├── UxRestrictionWatcher.kt 주행 제한 상태
│   ├── RestStopRepository.kt  쉼터 좌표(assets/rest_stops.json)
│   ├── RecordStore.kt         기록·설정 저장
│   └── DemoSources.kt         시연 모드(가상 주행 재생)
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
5. 차량 값을 바꾸기 어려울 때는 정차 화면의 **시연 모드**로 굽은 길 주행을 빠르게 재생할 수 있습니다(화면에 "시연 모드 · 가상 주행 데이터"로 표시).

## 테스트 환경
- Pleos Connect Emulator **v2.1.0**(Android 14, API 34, x86_64), 화면 **2560×1440**, 앱 창 1682×1210(멀티 윈도우)
- 확인한 것(2026-11-11): 차량 상태 구독(조향·브레이크·좌석 4곳·실내 온도·내기순환), GEAR_SELECTION 수신, 기어 D 전환 시 주행 화면 자동 전환, "멀미나요" → 실제 내비 위치 기준 쉼터 제안 → `addWaypoint` 요청이 Pleos 내비 앱에 전달됨
- 에뮬레이터에서 이번 주행 시간(Odometer)은 값이 오지 않아 앱 자체 타이머로 대신합니다.
- 시연 영상(2026-11-11): 실제 연동 장면 36초 + 시연 모드 시나리오 100초 + 15분 쪽잠 알람 10초
- 단위 테스트 15개: `./gradlew testDebugUnitTest`

## APK
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`(공모전용, 디버그 키 서명)
- 제출 시 `apk/팀명_앱이름_release.apk`로 저장소에 올립니다.

## 데이터와 라이선스
- 쉼터 좌표(`app/src/main/assets/rest_stops.json`): © OpenStreetMap contributors, **ODbL 1.0** (https://www.openstreetmap.org/copyright). 2026-11-11 Overpass API로 `highway=services|rest_area` 중 이름에 "휴게소·쉼터"가 들어간 1,011곳을 추렸습니다. 이 파일은 ODbL을 따릅니다.
- Pleos SDK와 에뮬레이터는 42dot의 Pleos Connect SDK 라이선스를 따르며, 이 저장소에는 SDK 파일을 넣지 않습니다(빌드 때 Maven으로 받음).
