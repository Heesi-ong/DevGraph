package com.devgraph.auth.presentation;

import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String slug) {
}
