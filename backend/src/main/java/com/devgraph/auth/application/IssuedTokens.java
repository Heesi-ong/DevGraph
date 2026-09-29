package com.devgraph.auth.application;

import java.util.UUID;

public record IssuedTokens(String accessToken, String rawRefreshToken, UUID familyId) {
}
