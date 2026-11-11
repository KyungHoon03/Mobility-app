package app.shimpyo.domain

/**
 * 쉼터에서 할 일. 의료 효과를 약속하지 않고, 연구에서 확인된 범위만 안내한다.
 */
object Advice {

    fun forCondition(condition: Condition, caffeineOk: Boolean): List<String> = when (condition) {
        Condition.DROWSINESS -> buildList {
            add("휴게소에 정차한 뒤 15분 이내로 쪽잠을 자 보세요. 알람을 15분에 맞춰 드려요.")
            if (caffeineOk) {
                add("커피를 마신 뒤 바로 쪽잠을 자면 가장 효과적이라는 연구가 있어요(카페인 함량은 음료마다 달라요).")
            } else {
                add("카페인이 맞지 않으면 쪽잠만으로도 도움이 돼요. 무카페인 껌이나 페퍼민트 향은 보조로만 쓰세요.")
            }
            add("에너지음료나 단 음료는 권하지 않아요. 오히려 나중에 반응이 느려졌다는 연구가 있어요.")
            add("찬바람과 라디오는 휴게소까지 가는 동안만 쓰는 임시방편이에요.")
        }
        Condition.MOTION_SICKNESS -> listOf(
            "휴대폰이나 책을 내려놓고 창밖 멀리 보기",
            "가능하면 앞좌석으로 옮겨 앉기",
            "안전할 때 창문을 조금 열기",
            "쉼터에서 잠깐 쉬어 가기",
        )
    }

    fun title(condition: Condition): String = when (condition) {
        Condition.DROWSINESS -> "졸음이 오기 전에 쉬어 가요"
        Condition.MOTION_SICKNESS -> "멀미가 심해지기 전에 쉬어 가요"
    }
}
