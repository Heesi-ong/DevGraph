package com.devgraph.search.application;

/**
 * 설계서 §9.5 초기 랭킹 가중치. 랭킹은 한 곳에 모아 두고 검색 회귀 테스트로 관리한다.
 * 제목 정확 일치가 본문 일치보다 항상 위에 오도록(완료 조건) 큰 값 차이를 유지한다.
 */
public final class SearchRanking {

	// 설계서 표의 100에서 상향했다. 100이면 "제목 접두어 70 + 코드 일치 40 + 제목 FTS"처럼 약한 일치가 겹친 항목이
	// 제목이 정확히 같은 항목을 앞지른다(fixture에서 확인: "JWT" 검색에서 코드에 jwt가 든 Snippet이 위로 올라옴).
	// 정확 일치가 나머지 모든 일치의 합(70+60+50+45+40+25=290)보다 크도록 300으로 둔다.
	public static final double EXACT_TITLE = 300;
	public static final double TITLE_PREFIX = 70;
	// 설계서 표에 없던 추가: 단어 중간의 부분 일치("sec" → "Spring Security")를 접두어보다 낮게 인정한다.
	public static final double TITLE_CONTAINS = 35;
	public static final double EXACT_TAG = 60;
	public static final double TITLE_FTS = 50; // × normalized_rank
	public static final double LANGUAGE_FRAMEWORK = 45;
	public static final double CODE = 40; // × similarity(부분 문자열 일치는 1.0)
	public static final double BODY_FTS = 25; // × normalized_rank
	// Error 메시지의 부분 일치(예외 이름 일부로도 찾는다). 코드 일치와 같은 비중이다.
	public static final double ERROR_MESSAGE = 40;
	public static final double FAVORITE = 5;
	public static final double RECENCY_MAX = 5;
	public static final int RECENCY_DAYS = 30;

	/** 유사 결과(zero-result fallback)에 쓰는 완화된 trigram 임계값(§9.5). */
	public static final double RELAXED_SIMILARITY = 0.1;

	private SearchRanking() {
	}
}
