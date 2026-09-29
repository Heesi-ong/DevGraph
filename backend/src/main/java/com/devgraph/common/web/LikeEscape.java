package com.devgraph.common.web;

/**
 * 사용자 입력의 `%` `_`가 LIKE 와일드카드로 해석되지 않게 한다. 이스케이프 문자로 `!`를 쓴다 — 백슬래시는
 * Java 문자열·HQL·SQL 세 계층에서 리터럴 처리가 달라 실수하기 쉬워서 피했다(백슬래시가 실제로 틀렸다고 확인한 것은 아니다).
 * 쿼리에는 반드시 `escape '!'`를 함께 쓴다. 와일드카드 문자가 문자 그대로 검색되는지는
 * 태그 검색과 Relation Picker 통합 테스트가 확인한다.
 */
public final class LikeEscape {

	public static final char CHAR = '!';

	private LikeEscape() {
	}

	public static String escape(String value) {
		return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	public static String contains(String value) {
		return "%" + escape(value) + "%";
	}

	public static String prefix(String value) {
		return escape(value) + "%";
	}
}
