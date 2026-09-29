package com.devgraph.snippet.infrastructure;

import java.time.Instant;

/** 버전 목록 한 줄. `codeLength`는 문자 수다(원문은 상세 조회에서만 준다). */
public record SnippetVersionRow(int versionNo, String changeSummary, Instant createdAt, int codeLength) {
}
