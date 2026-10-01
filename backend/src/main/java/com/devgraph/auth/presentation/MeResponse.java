package com.devgraph.auth.presentation;

// restriction: NONE | MUST_CHANGE_PASSWORD | DELETION_PENDING (§17.2.3). 화면이 제한 상태 안내를 보여 주는 데 쓴다.
public record MeResponse(UserResponse user, WorkspaceResponse workspace, String restriction) {
}
