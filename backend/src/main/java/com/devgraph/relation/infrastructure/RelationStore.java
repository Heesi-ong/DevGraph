package com.devgraph.relation.infrastructure;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.devgraph.common.error.ApiException;

/**
 * 설계서 §15.2: DB 제약 위반을 API 오류로 바꾸는 변환은 infrastructure 계층의 책임이다.
 * 이름은 V7 migration이 명시한 constraint 이름에 의존한다(`ddl-auto=validate`와 짝).
 * 애플리케이션 검증(self-loop, 중복 사전 조회)이 먼저 응답하므로 여기 도달하는 것은 주로 동시 요청 경합과 우회 경로다.
 */
@Component
public class RelationStore {

	private final RelationRepository repository;

	public RelationStore(RelationRepository repository) {
		this.repository = repository;
	}

	/** 즉시 flush해 제약 위반을 이 시점에 변환한다(커밋 시점에 터지면 500이 된다). */
	public RelationJpaEntity insert(RelationJpaEntity relation) {
		try {
			return repository.saveAndFlush(relation);
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	public RelationJpaEntity flush(RelationJpaEntity relation) {
		try {
			repository.flush();
			return relation;
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	private static ApiException translate(DataIntegrityViolationException e) {
		String constraint = constraintName(e);
		if ("uq_knowledge_relations__edge".equals(constraint)) {
			return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RELATION", "이미 같은 관계가 있습니다.");
		}
		if ("ck_knowledge_relations__no_self_loop".equals(constraint)) {
			return new ApiException(HttpStatus.BAD_REQUEST, "SELF_RELATION_NOT_ALLOWED", "자기 자신과는 연결할 수 없습니다.");
		}
		if (constraint != null && constraint.startsWith("fk_knowledge_relations__")) {
			return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RELATION_NODE", "연결할 수 없는 항목입니다.");
		}
		throw e; // 미등록 제약: 내부 상세를 숨긴 500(GlobalExceptionHandler)
	}

	private static String constraintName(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
				return simple(cve.getConstraintName());
			}
		}
		return null;
	}

	// Hibernate가 "public.name" 형태로 줄 수 있다.
	private static String simple(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1) : name;
	}
}
