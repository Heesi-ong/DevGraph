package com.devgraph.relation.infrastructure;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 설계서 §12.3 `relation_types`. 시스템 13종은 seed이며 애플리케이션이 수정·삭제하지 않는다(읽기 전용 매핑).
 * 사용자 정의 타입은 Growth 범위(§13.2)다.
 */
@Entity
@Immutable
@Table(name = "relation_types")
public class RelationTypeJpaEntity {

	@Id
	private UUID id;

	@Column(name = "workspace_id")
	private UUID workspaceId;

	@Column(name = "key", nullable = false)
	private String key;

	@Column(name = "forward_label", nullable = false)
	private String forwardLabel;

	@Column(name = "inverse_label", nullable = false)
	private String inverseLabel;

	private String description;

	@Column(nullable = false)
	private String directionality;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "allowed_source_types", nullable = false, columnDefinition = "text[]")
	private List<String> allowedSourceTypes;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "allowed_target_types", nullable = false, columnDefinition = "text[]")
	private List<String> allowedTargetTypes;

	@Column(name = "is_system", nullable = false)
	private boolean system;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	protected RelationTypeJpaEntity() {
	}

	public UUID getId() {
		return id;
	}

	public String getKey() {
		return key;
	}

	public String getForwardLabel() {
		return forwardLabel;
	}

	public String getInverseLabel() {
		return inverseLabel;
	}

	public String getDescription() {
		return description;
	}

	public boolean isSymmetric() {
		return "symmetric".equals(directionality);
	}

	public String getDirectionality() {
		return directionality;
	}

	public List<String> getAllowedSourceTypes() {
		return allowedSourceTypes;
	}

	public List<String> getAllowedTargetTypes() {
		return allowedTargetTypes;
	}

	public boolean isSystem() {
		return system;
	}
}
