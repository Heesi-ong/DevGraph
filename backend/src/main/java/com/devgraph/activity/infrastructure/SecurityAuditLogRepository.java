package com.devgraph.activity.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLogJpaEntity, UUID> {
}
