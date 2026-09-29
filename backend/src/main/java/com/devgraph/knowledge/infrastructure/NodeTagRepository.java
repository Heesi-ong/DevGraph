package com.devgraph.knowledge.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeTagRepository extends JpaRepository<NodeTagJpaEntity, NodeTagJpaEntity.Key> {

	List<NodeTagJpaEntity> findByNodeIdIn(Collection<UUID> nodeIds);

	void deleteByNodeIdAndTagIdIn(UUID nodeId, Collection<UUID> tagIds);
}
