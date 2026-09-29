package com.devgraph.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionFamilyRepository extends JpaRepository<AuthSessionFamilyJpaEntity, UUID> {

	Optional<AuthSessionFamilyJpaEntity> findByIdAndUserId(UUID id, UUID userId);

	List<AuthSessionFamilyJpaEntity> findByUserIdAndRevokedAtIsNullOrderByLastRotatedAtDesc(UUID userId);
}
