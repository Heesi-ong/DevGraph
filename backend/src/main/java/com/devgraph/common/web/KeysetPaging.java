package com.devgraph.common.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.devgraph.common.error.ApiException;

/** `updatedAt DESC, id ASC` keyset cursor(§14.7)의 인코딩·해석. 마이크로초 단위로 DB 정밀도에 맞춘다. */
public final class KeysetPaging {

	public record Position(Instant updatedAt, UUID id) {
	}

	private KeysetPaging() {
	}

	public static String encode(Instant updatedAt, UUID id) {
		return CursorCodec.encode(Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt)), id.toString());
	}

	/** cursor가 없으면 null. 형식이 깨졌으면 `400 INVALID_CURSOR`. */
	public static Position decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		List<String> parts = CursorCodec.decode(cursor, 2);
		try {
			return new Position(Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS),
					UUID.fromString(parts.get(1)));
		} catch (RuntimeException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
		}
	}
}
