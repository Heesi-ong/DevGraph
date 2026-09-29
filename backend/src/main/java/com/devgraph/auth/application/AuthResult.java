package com.devgraph.auth.application;

import java.util.UUID;

public record AuthResult(UUID userId, String email, String displayName, IssuedTokens tokens) {
}
