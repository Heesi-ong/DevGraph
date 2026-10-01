package com.devgraph.problem.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 설계서 §9.7 ERR-01/02 Error 해결 상태와 허용 전이.
 * `RESOLVED`/`WONT_FIX`는 종료 상태라 서로 바로 오갈 수 없고 `OPEN`으로 되돌린(재오픈) 뒤 바꾼다.
 * Solution 연결이 없는 `RESOLVED`는 경고만 하고 막지 않는다(§9.7) — 사용자가 실제 해결 없이 상태만 정리할 수 있다.
 */
public enum ResolutionStatus {
	OPEN, INVESTIGATING, RESOLVED, WONT_FIX;

	public Set<ResolutionStatus> next() {
		return switch (this) {
			case OPEN -> EnumSet.of(INVESTIGATING, RESOLVED, WONT_FIX);
			case INVESTIGATING -> EnumSet.of(OPEN, RESOLVED, WONT_FIX);
			case RESOLVED, WONT_FIX -> EnumSet.of(OPEN);
		};
	}

	public boolean canMoveTo(ResolutionStatus target) {
		return next().contains(target);
	}
}
