package com.devgraph.project.domain;

/**
 * 설계서 §9.6 PROJ-01 프로젝트 진행 상태. Node의 보관 상태(ACTIVE/ARCHIVED/TRASHED)와는 별개다 —
 * `ARCHIVED`는 "더 이상 다루지 않는 프로젝트"라는 업무 상태이고, 목록에서 숨기는 보관은 Node의 archive 동작이다.
 */
public enum ProjectStatus {
	ACTIVE, PAUSED, COMPLETED, ARCHIVED
}
