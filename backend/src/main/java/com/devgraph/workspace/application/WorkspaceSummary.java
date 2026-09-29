package com.devgraph.workspace.application;

import java.util.UUID;

public record WorkspaceSummary(UUID id, String name, String slug) {
}
