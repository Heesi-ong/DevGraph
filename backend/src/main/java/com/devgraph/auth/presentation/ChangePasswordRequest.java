package com.devgraph.auth.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 설계서 §14.2 PATCH /auth/password. */
public record ChangePasswordRequest(
		@NotBlank String currentPassword,
		@NotBlank @Size(min = 8, max = 255) String newPassword,
		Boolean revokeOtherSessions
) {
}
