package com.mplan.meetingrecorder

/**
 * 온디바이스 회의 요약 (인터넷·AI 서버 없이 폰 안에서 처리).
 * - 핵심 문장: 회의에서 자주 나온 단어를 많이 포함한 문장을 골라 시간순 정렬
 * - 결정사항 / 할 일: 한국어 표현 패턴으로 추출
 */
object Summarizer {

    data class Result(
        val keywords: List<String>,
        val keyPoints: List<Segment>,
        val decisions: List<Segment>,
        val actions: List<Segment>
    )

    private val decisionCues = listOf(
        "결정", "확정", "하기로", "기로 했", "합의", "정했", "정하죠", "채택", "승인", "으로 가죠", "로 가죠",
        "으로 가겠", "로 가겠", "그렇게 하죠", "그렇게 하겠", "진행하기로", "최종"
    )
    private val actionCues = listOf(
        "해야", "하겠습니다", "할게요", "할께요", "하겠어요", "해 주세요", "해주세요", "부탁", "까지",
        "담당", "준비", "검토", "보내", "전달", "공유해", "제출", "작성", "정리해", "확인해", "챙겨",
        "다음 주", "다음주", "내일", "이번 주", "이번주", "마감"
    )

    // 의미 없는 단어 (빈도 계산에서 제외)
    private val stopWords = setOf(
        "그", "이", "저", "것", "거", "수", "등", "좀", "잘", "더", "또", "및", "네", "예", "아", "어", "음", "응",
        "그래서", "그러면", "그런데", "근데", "그리고", "그냥", "이제", "지금", "약간", "일단", "뭐", "왜", "진짜",
        "정말", "너무", "많이", "다", "안", "못", "하는", "하고", "해서", "하면", "했는데", "합니다", "있는",
        "있습니다", "있어요", "없어요", "같아요", "같습니다", "생각", "부분", "이거", "그거", "저거", "여기",
        "거기", "우리", "저희", "제가", "그게", "이게", "이런", "그런", "어떤", "때문에", "이렇게", "그렇게",
        "되는", "된", "될", "하는데", "그럼", "네네", "아니", "맞아요", "감사합니다", "수고하셨습니다"
    )
    private val particles = listOf(
        "에서는", "에서도", "으로는", "이라고", "라고", "에서", "으로", "에게", "한테", "까지", "부터", "보다",
        "처럼", "이나", "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "와", "과", "만", "랑", "요"
    )

    private fun normalize(word: String): String? {
        var w = word.trim().trim('.', ',', '?', '!', '"', '\'', '·', '…', '(', ')', '[', ']')
        if (w.length < 2) return null
        for (p in particles) {
            if (w.length > p.length + 1 && w.endsWith(p)) { w = w.dropLast(p.length); break }
        }
        if (w.length < 2 || w in stopWords) return null
        if (w.all { it.isDigit() }) return null
        return w
    }

    fun summarize(segments: List<Segment>): Result {
        val segs = segments.filter { it.text.isNotBlank() }
        if (segs.isEmpty()) return Result(emptyList(), emptyList(), emptyList(), emptyList())

        // 단어 빈도
        val freq = HashMap<String, Int>()
        val segWords = segs.map { s -> s.text.split(Regex("\\s+")).mapNotNull { normalize(it) } }
        segWords.forEach { ws -> ws.toSet().forEach { freq[it] = (freq[it] ?: 0) + 1 } }

        val keywords = freq.entries.filter { it.value >= 2 }
            .sortedByDescending { it.value }.take(10).map { it.key }

        // 문장 점수 = 포함 단어 빈도 합 / 길이 보정
        val scored = segs.mapIndexed { i, s ->
            val ws = segWords[i]
            val score = if (ws.size < 3) 0.0
            else ws.toSet().sumOf { (freq[it] ?: 0).toDouble() } / Math.sqrt(ws.size.toDouble())
            s to score
        }
        val n = (segs.size / 8).coerceIn(3, 10)
        val keyPoints = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(n)
            .map { it.first }.sortedBy { it.startMs }

        val decisions = segs.filter { s -> decisionCues.any { s.text.contains(it) } }.take(15)
        val actions = segs.filter { s -> s !in decisions && actionCues.any { s.text.contains(it) } }.take(20)

        return Result(keywords, keyPoints, decisions, actions)
    }

    fun toMarkdown(title: String, durationMs: Long, r: Result): String = buildString {
        appendLine("# 회의 요약 — $title")
        appendLine("회의 길이: ${formatElapsed(durationMs)}")
        appendLine()
        if (r.keywords.isNotEmpty()) {
            appendLine("## 주요 키워드")
            appendLine(r.keywords.joinToString(" · "))
            appendLine()
        }
        appendLine("## 핵심 내용")
        if (r.keyPoints.isEmpty()) appendLine("- (추출된 내용 없음)")
        r.keyPoints.forEach { appendLine("- [${formatElapsed(it.startMs)}] ${it.text.trim()}") }
        appendLine()
        appendLine("## 결정사항")
        if (r.decisions.isEmpty()) appendLine("- (감지된 결정사항 없음)")
        r.decisions.forEach { appendLine("- [${formatElapsed(it.startMs)}] ${it.text.trim()}") }
        appendLine()
        appendLine("## 할 일")
        if (r.actions.isEmpty()) appendLine("- (감지된 할 일 없음)")
        r.actions.forEach { appendLine("- [ ] [${formatElapsed(it.startMs)}] ${it.text.trim()}") }
    }
}
