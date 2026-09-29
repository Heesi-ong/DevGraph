package com.devgraph.common.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;

/** 설계서 §14.1: size 기본 20, 최대 100. 범위를 벗어나면 조용히 자르지 않고 400으로 알린다. */
public final class PageSize {

	public static final int DEFAULT = 20;
	public static final int MAX = 100;

	private PageSize() {
	}

	public static int resolve(Integer requested) {
		return resolve(requested, DEFAULT);
	}

	/** 기본값이 다른 목록(예: Graph)용. 최대값은 같다. */
	public static int resolve(Integer requested, int defaultSize) {
		if (requested == null) {
			return defaultSize;
		}
		if (requested < 1 || requested > MAX) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("size", "RANGE_1_" + MAX)));
		}
		return requested;
	}
}
