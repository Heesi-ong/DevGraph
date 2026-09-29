package com.devgraph.common.security;

import java.util.UUID;

/** JWT 파싱 결과. 컨트롤러는 {@code @AuthenticationPrincipal AuthenticatedUser}로 받는다. */
public record AuthenticatedUser(UUID userId, UUID familyId, String restriction) {
}
