package com.devgraph.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSessionJpaEntity, UUID> {

	Optional<AuthSessionJpaEntity> findByRefreshTokenHash(byte[] refreshTokenHash);

	void deleteAllByFamilyId(UUID familyId);
}
