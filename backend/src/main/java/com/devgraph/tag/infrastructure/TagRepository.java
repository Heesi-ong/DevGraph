package com.devgraph.tag.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagJpaEntity, UUID> {

	Optional<TagJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

	Optional<TagJpaEntity> findByWorkspaceIdAndNormalizedName(UUID workspaceId, String normalizedName);

	List<TagJpaEntity> findByWorkspaceIdAndIdIn(UUID workspaceId, Collection<UUID> ids);
}
