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
}
