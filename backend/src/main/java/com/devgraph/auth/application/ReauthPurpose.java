package com.devgraph.auth.application;

/** 설계서 §17.2.2 재인증 목적 4종. `NODE_PERMANENT_DELETE`만 클라이언트가 targetId를 보낸다. */
public enum ReauthPurpose {
	EXPORT_CREATE, IMPORT_CREATE, NODE_PERMANENT_DELETE, ACCOUNT_DELETE_REQUEST
}
