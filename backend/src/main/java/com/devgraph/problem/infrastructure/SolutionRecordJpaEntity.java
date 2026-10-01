package com.devgraph.problem.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.devgraph.common.persistence.SubtypeRecord;

/** 설계서 §12.3 `solution_records`. */
@Entity
@Table(name = "solution_records")
public class SolutionRecordJpaEntity extends SubtypeRecord {

	@Column(name = "approach_md", nullable = false)
	private String approachMd;

	@Column(name = "steps_md")
	private String stepsMd;

	@Column(name = "verification_md")
	private String verificationMd;

	@Column(name = "tradeoffs_md")
	private String tradeoffsMd;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	protected SolutionRecordJpaEntity() {
	}

	public SolutionRecordJpaEntity(UUID nodeId, UUID workspaceId, String approachMd, String stepsMd,
			String verificationMd, String tradeoffsMd, Instant resolvedAt) {
		super(nodeId, workspaceId);
		edit(approachMd, stepsMd, verificationMd, tradeoffsMd, resolvedAt);
	}

	public String getApproachMd() {
		return approachMd;
	}

	public String getStepsMd() {
		return stepsMd;
	}

	public String getVerificationMd() {
		return verificationMd;
	}

	public String getTradeoffsMd() {
		return tradeoffsMd;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}

	public final void edit(String approachMd, String stepsMd, String verificationMd, String tradeoffsMd,
			Instant resolvedAt) {
		this.approachMd = approachMd;
		this.stepsMd = stepsMd;
		this.verificationMd = verificationMd;
		this.tradeoffsMd = tradeoffsMd;
		this.resolvedAt = resolvedAt;
	}
}
