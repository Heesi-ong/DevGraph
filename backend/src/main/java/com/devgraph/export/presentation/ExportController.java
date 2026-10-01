package com.devgraph.export.presentation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.web.FieldRules;
import com.devgraph.common.web.IpPrefixExtractor;
import com.devgraph.export.application.ExportService;
import com.devgraph.export.application.ExportService.JobView;

/**
 * 설계서 §14.6 Export API. `GET /exports`(최근 job 목록)는 새로고침 뒤에도 진행 상황을 이어 보여 주려는 보조 조회다.
 * 다운로드 URL은 브라우저가 링크로 여는 일회성 token URL이라 Bearer 없이 접근한다(SecurityConfig의 permitAll).
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

	public record CreateExportRequest(Boolean includeArchived, String format) {
	}

	public record CreatedResponse(UUID jobId, String status) {
	}

	private final ExportService service;

	public ExportController(ExportService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<CreatedResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestBody(required = false) CreateExportRequest request,
			@RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken, HttpServletRequest http) {
		if (request != null && request.format() != null && !"ZIP".equalsIgnoreCase(request.format())) {
			throw FieldRules.validation("format", "UNSUPPORTED_FORMAT");
		}
		boolean includeArchived = request != null && Boolean.TRUE.equals(request.includeArchived());
		UUID id = service.create(user, reauthToken, includeArchived, IpPrefixExtractor.from(http));
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CreatedResponse(id, "PENDING"));
	}

	@GetMapping
	public List<JobView> recent(@AuthenticationPrincipal AuthenticatedUser user) {
		return service.recent(user);
	}

	@GetMapping("/{jobId}")
	public JobView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobId) {
		return service.get(user, jobId);
	}

	@GetMapping("/{jobId}/download")
	public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID jobId, @RequestParam(required = false) String token)
			throws IOException {
		ExportService.Download download = service.openDownload(jobId, token);
		long size = Files.size(download.file());
		StreamingResponseBody body = (OutputStream out) -> {
			try {
				Files.copy(download.file(), out);
			} finally {
				service.finishDownload(download);
			}
		};
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("application/zip"))
				.contentLength(size)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename("devgraph-export-" + java.time.LocalDate.now() + ".zip").build().toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(body);
	}
}
