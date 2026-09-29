package com.devgraph.common.web;

import java.util.List;

/** 설계서 §14.7 cursor 목록 공통 구조: {items, cursor, hasMore}. */
public record PageResponse<T>(List<T> items, String cursor, boolean hasMore) {
}
