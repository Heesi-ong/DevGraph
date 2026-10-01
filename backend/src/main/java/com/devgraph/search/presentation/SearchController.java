package com.devgraph.search.presentation;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.search.application.SearchService;
import com.devgraph.search.application.SearchViews.SearchPage;

/** 설계서 §14.6 `GET /search`. 휴지통은 항상 제외하고 `archived=true`일 때만 보관 항목을 포함한다. */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

	private final SearchService service;

	public SearchController(SearchService service) {
		this.service = service;
	}

	@GetMapping
	public SearchPage search(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam(required = false) String q,
			@RequestParam(required = false) List<NodeType> types, @RequestParam(required = false) UUID tagId,
			@RequestParam(required = false) String language, @RequestParam(required = false) String framework,
			@RequestParam(defaultValue = "false") boolean archived, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) String scope) {
		if (scope != null && !scope.isBlank()) {
			if (!"snippetHistory".equals(scope)) {
				throw com.devgraph.common.web.FieldRules.validation("scope", "UNSUPPORTED_SCOPE");
			}
			return service.searchHistory(user.userId(), q, tagId, language, framework, archived, cursor, size);
		}
		return service.search(user.userId(), q, types, tagId, language, framework, archived, cursor, size);
	}
}
