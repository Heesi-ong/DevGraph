package com.devgraph.auth.presentation;

import java.util.List;

public record SessionListResponse(List<SessionSummaryResponse> items) {
}
