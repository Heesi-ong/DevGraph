package com.devgraph.auth.presentation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 설계서 §14.2 POST /auth/signup. v1.4에서 termsVersion은 제거했다(§9.1 AUTH-01). */
public record SignupRequest(
		@Email @NotBlank String email,
		@NotBlank @Size(max = 100) String displayName,
		@NotBlank @Size(min = 8, max = 255) String password
) {
}
