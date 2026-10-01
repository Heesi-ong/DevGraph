package com.devgraph.problem.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.devgraph.common.persistence.SubtypeRecord;
import com.devgraph.problem.domain.ResolutionStatus;

/** 설계서 §12.3 `error_records`. `search_vector`는 DB가 생성하는 컬럼이라 매핑하지 않는다. */
@Entity
@Table(name = "error_records")
public class ErrorRecordJpaEntity extends SubtypeRecord {

	@Column(name = "error_message", nullable = false)
	private String errorMessage;

	private String environment;

	@Column(name = "reproduction_steps_md")
	private String reproductionStepsMd;

	@Column(name = "cause_hypothesis_md")
	private String causeHypothesisMd;

	@Column(name = "resolution_status", nullable = false)
	private String resolutionStatus;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	protected ErrorRecordJpaEntity() {
	}

	public ErrorRecordJpaEntity(UUID nodeId, UUID workspaceId, String errorMessage, String environment,
			String reproductionStepsMd, String causeHypothesisMd, Instant occurredAt) {
		super(nodeId, workspaceId);
		this.errorMessage = errorMessage;
		this.environment = environment;
		this.reproductionStepsMd = reproductionStepsMd;
		this.causeHypothesisMd = causeHypothesisMd;
		this.occurredAt = occurredAt;
		this.resolutionStatus = ResolutionStatus.OPEN.name();
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getEnvironment() {
		return environment;
	}

	public String getReproductionStepsMd() {
		return reproductionStepsMd;
	}

	public String getCauseHypothesisMd() {
		return causeHypothesisMd;
	}

	public ResolutionStatus getResolutionStatus() {
		return ResolutionStatus.valueOf(resolutionStatus);
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public void edit(String errorMessage, String environment, String reproductionStepsMd, String causeHypothesisMd,
			Instant occurredAt) {
		this.errorMessage = errorMessage;
		this.environment = environment;
		this.reproductionStepsMd = reproductionStepsMd;
		this.causeHypothesisMd = causeHypothesisMd;
		this.occurredAt = occurredAt;
	}

	/** DB CHECK와 같은 불변식: `RESOLVED`일 때만 resolved_at이 있다. */
	public void moveTo(ResolutionStatus status, Instant resolvedAt) {
		this.resolutionStatus = status.name();
		this.resolvedAt = status == ResolutionStatus.RESOLVED ? resolvedAt : null;
	}
}
