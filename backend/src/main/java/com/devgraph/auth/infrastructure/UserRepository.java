package com.devgraph.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserJpaEntity, UUID> {

	Optional<UserJpaEntity> findByEmailNormalized(String emailNormalized);

	boolean existsByEmailNormalized(String emailNormalized);
}
