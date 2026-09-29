package com.devgraph.auth.presentation;

public record AuthResponse(UserResponse user, String accessToken) {
}
