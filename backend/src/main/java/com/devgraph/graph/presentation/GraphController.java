package com.devgraph.graph.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.graph.application.GraphService;
import com.devgraph.graph.application.GraphViews.GraphResponse;
import com.devgraph.knowledge.domain.NodeType;

/**
 * 설계서 §14.5 Graph API. `PUT /graph/layout`(GRPH-06, SHOULD)은 이번 Phase 범위에서 제외했다.
 * 휴지통 Node는 항상 제외되고, `archived=true`일 때만 보관 Node를 포함한다(GRPH-04).
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

	private final GraphService service;

	public GraphController(GraphService service) {
		this.service = service;
	}

	@GetMapping("/focus/{nodeId}")
	public GraphResponse focus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID nodeId,
			@RequestParam(required = false) Integer depth, @RequestParam(required = false) List<NodeType> nodeTypes,
			@RequestParam(required = false) List<String> relationTypes,
			@RequestParam(required = false) Integer maxNodes, @RequestParam(defaultValue = "false") boolean archived) {
		return service.focus(user.userId(), nodeId, depth, nodeTypes, relationTypes, maxNodes, archived);
	}

	@GetMapping("/workspace")
	public GraphResponse workspace(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) List<NodeType> nodeTypes,
			@RequestParam(required = false) List<String> relationTypes, @RequestParam(required = false) UUID tag,
			@RequestParam(defaultValue = "false") boolean archived, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return service.workspace(user.userId(), nodeTypes, relationTypes, tag, archived, cursor, size);
	}
}
