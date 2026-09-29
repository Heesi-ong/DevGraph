package com.devgraph.auth.infrastructure;

/** 설계서 §17.2.1 `auth_session_families.revoke_reason` 허용값. */
public enum RevokeReason {
	USER_REQUEST,
	PASSWORD_CHANGED,
	ACCOUNT_DELETION_REQUESTED,
	REUSE_DETECTED,
	ABSOLUTE_EXPIRED
}
