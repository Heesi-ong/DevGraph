package com.devgraph.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<FavoriteJpaEntity, FavoriteJpaEntity.Key> {

	@Query("select f.nodeId from FavoriteJpaEntity f where f.userId = :userId and f.nodeId in :nodeIds")
	List<UUID> findFavoriteNodeIds(@Param("userId") UUID userId, @Param("nodeIds") Collection<UUID> nodeIds);

	// 동시에 같은 즐겨찾기를 두 번 요청해도 PK 충돌 없이 멱등이 되도록 DB가 충돌을 흡수한다.
	@Modifying
	@Query(nativeQuery = true, value = """
			INSERT INTO favorites (workspace_id, user_id, node_id) VALUES (:workspaceId, :userId, :nodeId)
			ON CONFLICT (user_id, node_id) DO NOTHING""")
	int insertIfAbsent(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId,
			@Param("nodeId") UUID nodeId);

	@Modifying
	@Query("delete from FavoriteJpaEntity f where f.userId = :userId and f.nodeId = :nodeId")
	int deleteFavorite(@Param("userId") UUID userId, @Param("nodeId") UUID nodeId);
}
