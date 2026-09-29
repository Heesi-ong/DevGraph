package com.devgraph.knowledge.domain;

/** 설계서 §11.2 Knowledge Node 타입. 모든 타입이 knowledge_nodes.id를 공유한다. */
public enum NodeType {
	CONCEPT,
	NOTE,
	SNIPPET,
	ERROR,
	SOLUTION,
	RESOURCE,
	PROJECT
}
