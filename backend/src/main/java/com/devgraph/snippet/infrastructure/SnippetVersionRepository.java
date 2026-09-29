package com.devgraph.snippet.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SnippetVersionRepository extends JpaRepository<SnippetVersionJpaEntity, UUID> {

	Optional<SnippetVersionJpaEntity> findBySnippetNodeIdAndWorkspaceIdAndVersionNo(UUID snippetNodeId,
			UUID workspaceId, int versionNo);

	/** 버전 목록(최신순). after가 null이면 처음부터. 목록은 코드 원문을 싣지 않기 위해 별도 projection을 쓴다. */
	@Query("""
			select new com.devgraph.snippet.infrastructure.SnippetVersionRow(v.versionNo, v.changeSummary, v.createdAt,
			       length(v.code))
			from SnippetVersionJpaEntity v
			where v.snippetNodeId = :nodeId and v.workspaceId = :workspaceId
			  and (:after is null or v.versionNo < :after)
			order by v.versionNo desc""")
	List<SnippetVersionRow> findRows(@Param("nodeId") UUID nodeId, @Param("workspaceId") UUID workspaceId,
			@Param("after") Integer after, Pageable pageable);
}
