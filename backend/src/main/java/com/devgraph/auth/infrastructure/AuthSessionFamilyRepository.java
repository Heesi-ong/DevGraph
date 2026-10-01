package com.devgraph.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionFamilyRepository extends JpaRepository<AuthSessionFamilyJpaEntity, UUID> {

	Optional<AuthSessionFamilyJpaEntity> findByIdAndUserId(UUID id, UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from AuthSessionFamilyJpaEntity f where f.id = :id")
	Optional<AuthSessionFamilyJpaEntity> findLockedById(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from AuthSessionFamilyJpaEntity f where f.id = :id and f.userId = :userId")
	Optional<AuthSessionFamilyJpaEntity> findLockedByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	List<AuthSessionFamilyJpaEntity> findByUserIdAndRevokedAtIsNullOrderByLastRotatedAtDesc(UUID userId);

	/**
	 * 한 사용자의 살아 있는 세션(family)을 한꺼번에 폐기한다. `exceptFamilyId`가 null이면 전부.
	 * 폐기된 family는 refresh와 재인증 토큰 소비에서 모두 거부된다(§17.2.1, §17.2.2).
	 */
	@org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
	@org.springframework.data.jpa.repository.Query("""
			update AuthSessionFamilyJpaEntity f set f.revokedAt = current_timestamp, f.revokeReason = :reason
			where f.userId = :userId and f.revokedAt is null and (:except is null or f.id <> :except)""")
	int revokeAll(@org.springframework.data.repository.query.Param("userId") UUID userId,
			@org.springframework.data.repository.query.Param("reason") RevokeReason reason,
			@org.springframework.data.repository.query.Param("except") UUID exceptFamilyId);
}
