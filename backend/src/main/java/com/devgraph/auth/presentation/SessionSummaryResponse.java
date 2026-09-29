package com.devgraph.auth.presentation;

import java.time.Instant;
import java.util.UUID;

/** 설계서 §14.2 GET /auth/sessions — id는 rotation row가 아니라 family id다(§17.2.1). */
public record SessionSummaryResponse(
		UUID id,
		boolean current,
		String deviceLabel,
		Instant createdAt,
		Instant lastRotatedAt,
		Instant absoluteExpiresAt
) {
}
