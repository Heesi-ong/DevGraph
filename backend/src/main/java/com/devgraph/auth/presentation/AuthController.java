package com.devgraph.auth.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.auth.application.AuthResult;
import com.devgraph.auth.application.IssuedTokens;
import com.devgraph.auth.application.LoginService;
import com.devgraph.auth.application.MeQueryService;
import com.devgraph.auth.application.RefreshService;
import com.devgraph.auth.application.SessionManagementService;
import com.devgraph.auth.application.SignupService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyJpaEntity;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.security.AuthCookies;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.security.TokenHasher;
import com.devgraph.common.web.IpPrefixExtractor;

/** 설계서 §14.2 Auth API. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final SignupService signupService;
	private final LoginService loginService;
	private final RefreshService refreshService;
	private final MeQueryService meQueryService;
	private final SessionManagementService sessionManagementService;
	private final AuthCookies authCookies;

	public AuthController(SignupService signupService, LoginService loginService, RefreshService refreshService,
			MeQueryService meQueryService, SessionManagementService sessionManagementService, AuthCookies authCookies) {
		this.signupService = signupService;
		this.loginService = loginService;
		this.refreshService = refreshService;
		this.meQueryService = meQueryService;
		this.sessionManagementService = sessionManagementService;
		this.authCookies = authCookies;
	}

	@PostMapping("/signup")
	public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		AuthResult result = signupService.signup(request.email(), request.displayName(), request.password(),
				IpPrefixExtractor.from(http));
		return withAuthCookies(HttpStatus.CREATED, result.tokens())
				.body(new AuthResponse(toUserResponse(result), result.tokens().accessToken()));
	}

	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		AuthResult result = loginService.login(request.email(), request.password(), IpPrefixExtractor.from(http));
		return withAuthCookies(HttpStatus.OK, result.tokens())
				.body(new AuthResponse(toUserResponse(result), result.tokens().accessToken()));
	}

	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(HttpServletRequest http) {
		String rawRefreshToken = readCookie(http, AuthCookies.REFRESH_COOKIE)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "세션이 만료되었습니다."));
		IssuedTokens tokens = refreshService.refresh(rawRefreshToken, IpPrefixExtractor.from(http));
		return withAuthCookies(HttpStatus.OK, tokens).body(new RefreshResponse(tokens.accessToken()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest http) {
		sessionManagementService.revoke(user.userId(), user.familyId(), RevokeReason.USER_REQUEST,
				IpPrefixExtractor.from(http));
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, authCookies.expiredRefreshCookie().toString())
				.build();
	}

	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
		MeQueryService.Me me = meQueryService.getMe(user.userId());
		WorkspaceResponse workspace = me.workspace() == null ? null
				: new WorkspaceResponse(me.workspace().id(), me.workspace().name(), me.workspace().slug());
		return new MeResponse(new UserResponse(me.user().getId(), me.user().getEmail(), me.user().getDisplayName()),
				workspace);
	}

	@GetMapping("/sessions")
	public SessionListResponse sessions(@AuthenticationPrincipal AuthenticatedUser user) {
		List<SessionSummaryResponse> items = sessionManagementService.listActiveSessions(user.userId()).stream()
				.map(family -> toSummary(family, user.familyId()))
				.toList();
		return new SessionListResponse(items);
	}

	@DeleteMapping("/sessions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			HttpServletRequest http) {
		sessionManagementService.revoke(user.userId(), id, RevokeReason.USER_REQUEST, IpPrefixExtractor.from(http));
	}

	private ResponseEntity.BodyBuilder withAuthCookies(HttpStatus status, IssuedTokens tokens) {
		String csrfValue = TokenHasher.newOpaqueToken();
		return ResponseEntity.status(status)
				.header(HttpHeaders.SET_COOKIE, authCookies.refreshCookie(tokens.rawRefreshToken()).toString())
				.header(HttpHeaders.SET_COOKIE, authCookies.csrfCookie(csrfValue).toString());
	}

	private java.util.Optional<String> readCookie(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return java.util.Optional.empty();
		}
		return java.util.Arrays.stream(cookies).filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst();
	}

	private UserResponse toUserResponse(AuthResult result) {
		return new UserResponse(result.userId(), result.email(), result.displayName());
	}

	private SessionSummaryResponse toSummary(AuthSessionFamilyJpaEntity family, UUID currentFamilyId) {
		return new SessionSummaryResponse(family.getId(), family.getId().equals(currentFamilyId),
				family.getDeviceLabel(), family.getCreatedAt(), family.getLastRotatedAt(),
				family.getAbsoluteExpiresAt());
	}
}
