package com.devgraph.activity.application;

import java.util.UUID;

/** 다른 모듈이 발행하는 일반 활동 이벤트. 커밋된 뒤에만 기록된다(§15.2 AFTER_COMMIT, 유실 허용). */
public record ActivityEvent(UUID workspaceId, UUID actorUserId, String action, String objectType, UUID objectId) {
}
