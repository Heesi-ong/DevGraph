package com.devgraph.knowledge.domain;

/** 설계서 §11.3 상태. 영구 삭제(PURGED)는 행이 사라지므로 상태로 두지 않는다. */
public enum NodeStatus {
	ACTIVE,
	ARCHIVED,
	TRASHED
}
