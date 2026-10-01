package com.devgraph.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSessionJpaEntity, UUID> {

	Optional<AuthSessionJpaEntity> findByRefreshTokenHash(byte[] refreshTokenHash);

	void deleteAllByFamilyId(UUID familyId);

	/** 세션 목록의 기기 정보(IP 대역)는 해당 family의 가장 최신 rotation row에서 가져온다(§14.2). */
	Optional<AuthSessionJpaEntity> findFirstByFamilyIdOrderByCreatedAtDesc(UUID familyId);

	List<AuthSessionJpaEntity> findTop2ByFamilyIdOrderByCreatedAtDesc(UUID familyId);
}
