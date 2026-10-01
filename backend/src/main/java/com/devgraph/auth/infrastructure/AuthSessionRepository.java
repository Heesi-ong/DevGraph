package com.devgraph.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSessionJpaEntity, UUID> {

	Optional<AuthSessionJpaEntity> findByRefreshTokenHash(byte[] refreshTokenHash);

	@Query("select s.familyId from AuthSessionJpaEntity s where s.refreshTokenHash = :hash")
	Optional<UUID> findFamilyIdByTokenHash(@Param("hash") byte[] hash);

	/** DB가 단일 소비를 결정한다. 새 rotation 발급과 같은 트랜잭션이므로 실패 시 소비도 롤백된다. */
	@Modifying
	@Query(value = """
			UPDATE auth_sessions SET rotated_at = now()
			WHERE id = :id AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now()
			""", nativeQuery = true)
	int consume(@Param("id") UUID id);

	void deleteAllByFamilyId(UUID familyId);
}
