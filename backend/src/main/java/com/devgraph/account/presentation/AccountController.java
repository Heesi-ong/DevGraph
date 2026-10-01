package com.devgraph.account.presentation;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.account.application.AccountService;
import com.devgraph.common.security.AuthCookies;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.web.IpPrefixExtractor;

/** 설계서 §14.2 계정 탈퇴. 탈퇴 취소는 제한 세션(`DELETION_PENDING`)에서도 호출할 수 있다(§17.2.3). */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

	public record DeletionRequest(@NotBlank String confirmation) {
	}

	public record DeletionResponse(Instant scheduledAt) {
	}

	private final AccountService service;
	private final AuthCookies authCookies;

	public AccountController(AccountService service, AuthCookies authCookies) {
		this.service = service;
		this.authCookies = authCookies;
	}

	/** 요청한 기기의 세션도 폐기되므로 refresh 쿠키를 함께 만료시킨다. */
	@PostMapping("/deletion-request")
	public ResponseEntity<DeletionResponse> requestDeletion(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody DeletionRequest request,
			@RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken, HttpServletRequest http) {
		Instant scheduledAt = service.requestDeletion(user, request.confirmation(), reauthToken,
				IpPrefixExtractor.from(http));
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, authCookies.expiredRefreshCookie().toString())
				.body(new DeletionResponse(scheduledAt));
	}

	@PostMapping("/deletion-cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancelDeletion(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
		service.cancelDeletion(user.userId(), IpPrefixExtractor.from(http));
	}
}
