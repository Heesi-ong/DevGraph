package com.devgraph.auth.presentation;

import java.time.Instant;

/** 설계서 §17.2.2. `reauthToken`은 이 응답에서만 볼 수 있고(서버는 해시만 저장) 1회용이다. */
public record ReauthResponse(String reauthToken, String purpose, Instant expiresAt) {
}
