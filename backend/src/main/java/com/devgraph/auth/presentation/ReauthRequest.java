package com.devgraph.auth.presentation;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

/** 설계서 §14.2 POST /auth/reauth. `purpose`는 서비스가 enum으로 검증해 전용 오류 코드로 답한다. */
public record ReauthRequest(@NotBlank String password, String purpose, UUID targetId) {
}
