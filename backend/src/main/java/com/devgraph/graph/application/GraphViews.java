package com.devgraph.graph.application;

import java.util.List;
import java.util.UUID;

/** 설계서 §14.5 `GraphResponse`. */
public final class GraphViews {

	private GraphViews() {
	}

	public record GraphNode(UUID id, String type, String title, String status, int depth) {
	}

	public record GraphEdge(UUID id, UUID source, UUID target, String type, String label, boolean directed) {
	}

	public record ExpansionCandidate(UUID nodeId, String title, String type, String viaRelation) {
	}

	public record AppliedFilters(int depth, List<String> nodeTypes, List<String> relationTypes, boolean includeArchived) {
	}

	public record Limits(int maxNodes, int maxEdges) {
	}

	public record GraphResponse(List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated,
			String truncationReason, AppliedFilters appliedFilters, List<ExpansionCandidate> nextExpansionCandidates,
			String cursor, Limits limits) {
	}
}
