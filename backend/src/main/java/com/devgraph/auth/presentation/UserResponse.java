package com.devgraph.auth.presentation;

import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName) {
}
