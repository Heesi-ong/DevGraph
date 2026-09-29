package com.devgraph.snippet.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SnippetRepository extends JpaRepository<SnippetJpaEntity, UUID> {

	Optional<SnippetJpaEntity> findByNodeIdAndWorkspaceId(UUID nodeId, UUID workspaceId);

	// 동시 복사에서도 카운트가 유실되지 않도록 DB에서 증가시킨다(@Modifying이라 영속성 컨텍스트는 쓰지 않는다).
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
			UPDATE snippets SET use_count = use_count + 1, last_used_at = now()
			WHERE node_id = :nodeId AND workspace_id = :workspaceId""")
	int recordUse(@Param("nodeId") UUID nodeId, @Param("workspaceId") UUID workspaceId);
}
