package com.devgraph.tag.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.CursorCodec;
import com.devgraph.common.web.PageResponse;
import com.devgraph.common.web.PageSize;
import com.devgraph.tag.infrastructure.TagJpaEntity;
import com.devgraph.tag.infrastructure.TagQueryRepository;
import com.devgraph.tag.infrastructure.TagRepository;

/** 설계서 §9.2 KNOW-06, §14.3 Tag API. Workspace 안에서 이름(대소문자·공백 무시)이 유일하다. */
@Service
public class TagService {

	private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
	private static final int MAX_NAME_LENGTH = 50;

	private final TagRepository tagRepository;
	private final TagQueryRepository tagQueryRepository;

	public TagService(TagRepository tagRepository, TagQueryRepository tagQueryRepository) {
		this.tagRepository = tagRepository;
		this.tagQueryRepository = tagQueryRepository;
	}

	@Transactional(readOnly = true)
	public PageResponse<TagView> list(UUID workspaceId, String q, String cursor, Integer requestedSize) {
		int size = PageSize.resolve(requestedSize);
		String prefix = (q == null || q.isBlank()) ? null : normalize(q);
		String afterName = null;
		UUID afterId = null;
		if (cursor != null && !cursor.isBlank()) {
			List<String> parts = CursorCodec.decode(cursor, 2);
			afterName = parts.get(0);
			afterId = parseUuid(parts.get(1));
		}
		List<TagJpaEntity> rows = tagQueryRepository.search(workspaceId, prefix, afterName, afterId, size + 1);
		boolean hasMore = rows.size() > size;
		List<TagJpaEntity> page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = hasMore
				? CursorCodec.encode(page.get(page.size() - 1).getNormalizedName(),
						page.get(page.size() - 1).getId().toString())
				: null;
		return new PageResponse<>(page.stream().map(TagService::toView).toList(), nextCursor, hasMore);
	}

	@Transactional
	public TagView create(UUID workspaceId, String name, String color) {
		String trimmed = cleanName(name);
		String normalized = normalize(trimmed);
		validateColor(color);
		if (tagRepository.findByWorkspaceIdAndNormalizedName(workspaceId, normalized).isPresent()) {
			throw tagExists();
		}
		try {
			TagJpaEntity saved = tagRepository.saveAndFlush(
					new TagJpaEntity(UUID.randomUUID(), workspaceId, trimmed, normalized, color));
			return toView(saved);
		} catch (DataIntegrityViolationException e) {
			// 동시에 같은 이름을 만든 요청이 UNIQUE(uq_tags__workspace_name)에 걸린 경우(§15.3 최종 방어선).
			throw tagExists();
		}
	}

	@Transactional
	public TagView update(UUID workspaceId, UUID tagId, String name, String color) {
		TagJpaEntity tag = tagRepository.findByIdAndWorkspaceId(tagId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "태그를 찾을 수 없습니다."));
		String trimmed = name == null ? tag.getName() : cleanName(name);
		String normalized = normalize(trimmed);
		validateColor(color);
		tagRepository.findByWorkspaceIdAndNormalizedName(workspaceId, normalized)
				.filter(existing -> !existing.getId().equals(tagId))
				.ifPresent(existing -> {
					throw tagExists();
				});
		tag.update(trimmed, normalized, color == null ? tag.getColor() : color);
		try {
			tagRepository.flush();
		} catch (DataIntegrityViolationException e) {
			throw tagExists();
		}
		return toView(tag);
	}

	/** 다른 모듈(knowledge)이 응답에 태그 정보를 채울 때 쓴다. 다른 Workspace의 태그는 절대 반환하지 않는다. */
	@Transactional(readOnly = true)
	public Map<UUID, TagView> findViews(UUID workspaceId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return tagRepository.findByWorkspaceIdAndIdIn(workspaceId, ids).stream()
				.collect(Collectors.toMap(TagJpaEntity::getId, TagService::toView));
	}

	/** Node 생성/수정 시 tagIds가 모두 이 Workspace의 태그인지 검증한다. */
	@Transactional(readOnly = true)
	public void requireAllExist(UUID workspaceId, Collection<UUID> ids) {
		Set<UUID> unique = new HashSet<>(ids);
		Set<UUID> found = tagRepository.findByWorkspaceIdAndIdIn(workspaceId, unique).stream()
				.map(TagJpaEntity::getId)
				.collect(Collectors.toSet());
		if (!found.containsAll(unique)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("tagIds", "UNKNOWN_TAG")));
		}
	}

	/** trim + 연속 공백 축약 + 소문자. "Spring  Boot"와 "spring boot"는 같은 태그다. */
	static String normalize(String name) {
		return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private static String cleanName(String name) {
		String cleaned = name == null ? "" : name.trim().replaceAll("\\s+", " ");
		if (cleaned.isEmpty() || cleaned.length() > MAX_NAME_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("name", "LENGTH_1_" + MAX_NAME_LENGTH)));
		}
		return cleaned;
	}

	private static void validateColor(String color) {
		if (color != null && !COLOR.matcher(color).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("color", "PATTERN_HEX")));
		}
	}

	private static ApiException tagExists() {
		return new ApiException(HttpStatus.CONFLICT, "TAG_EXISTS", "이미 존재하는 태그입니다.");
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
		}
	}

	private static TagView toView(TagJpaEntity entity) {
		return new TagView(entity.getId(), entity.getName(), entity.getColor());
	}
}
