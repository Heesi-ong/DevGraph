package com.devgraph.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 설계서 §17.2: Argon2id 우선. */
@Configuration
public class PasswordEncoderConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		// saltLength, hashLength, parallelism, memory(KB), iterations — Spring Security 권장 기본값.
		return new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 2);
	}
}
