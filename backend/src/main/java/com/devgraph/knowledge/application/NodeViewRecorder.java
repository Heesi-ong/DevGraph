package com.devgraph.knowledge.application;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설계서 §9.2 KNOW-08: 상세 조회의 "최근 조회 시각"은 비동기로 갱신한다. 조회 응답을 늦추거나 실패시키지
 * 않으며, 기록이 유실돼도 핵심 흐름에는 영향이 없다. 수정일(updatedAt)과는 별개다.
 */
@Component
public class NodeViewRecorder {

	private static final Logger log = LoggerFactory.getLogger(NodeViewRecorder.class);

	@PersistenceContext
	private EntityManager entityManager;

	@Async
	@Transactional
	public void record(UUID workspaceId, UUID userId, UUID nodeId) {
		try {
			entityManager.createNativeQuery("""
					INSERT INTO node_views (workspace_id, user_id, node_id, last_viewed_at, view_count)
					VALUES (:ws, :user, :node, now(), 1)
					ON CONFLICT (user_id, node_id)
					DO UPDATE SET last_viewed_at = now(), view_count = node_views.view_count + 1""")
					.setParameter("ws", workspaceId)
					.setParameter("user", userId)
					.setParameter("node", nodeId)
					.executeUpdate();
		} catch (RuntimeException e) {
			log.warn("최근 조회 기록 실패(유실 허용): node={}", nodeId, e);
		}
	}
}
