package com.devgraph.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;


/** 설계서 §17.6 슬라이딩 윈도: 한도, 창 만료, Retry-After, 키 격리, 실패만 세기. 시간은 가짜 시계로 고정한다. */
class RateLimiterTest {

	private final AtomicLong nanos = new AtomicLong();
	private final RateLimiter limiter = new RateLimiter(nanos::get);
	private static final Duration MINUTE = Duration.ofMinutes(1);

	private void advance(Duration d) {
		nanos.addAndGet(d.toNanos());
	}

	@Test
	void allowsUpToTheLimitThenRejectsWithRetryAfterUntilTheWindowSlides() {
		for (int i = 0; i < 3; i++) {
			limiter.acquire("k", 3, MINUTE);
			advance(Duration.ofSeconds(10));
		}
		// 첫 이벤트는 30초 전 → 창(60초)이 풀리려면 30초 더 필요하다.
		assertThatThrownBy(() -> limiter.acquire("k", 3, MINUTE)).isInstanceOfSatisfying(RateLimitedException.class,
				e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(30));
		advance(Duration.ofSeconds(29));
		assertThatThrownBy(() -> limiter.acquire("k", 3, MINUTE)).isInstanceOf(RateLimitedException.class);
		advance(Duration.ofSeconds(1)); // 첫 이벤트가 창 밖으로
		limiter.acquire("k", 3, MINUTE);
	}

	@Test
	void keysAreIndependent() {
		limiter.acquire("a", 1, MINUTE);
		assertThatThrownBy(() -> limiter.acquire("a", 1, MINUTE)).isInstanceOf(RateLimitedException.class);
		limiter.acquire("b", 1, MINUTE);
	}

	@Test
	void checkDoesNotRecordAndHitDoes() {
		for (int i = 0; i < 10; i++) {
			limiter.check("login", 2, MINUTE); // 검사만 하면 아무리 불러도 한도에 닿지 않는다.
		}
		limiter.hit("login", MINUTE);
		limiter.check("login", 2, MINUTE);
		limiter.hit("login", MINUTE);
		assertThatThrownBy(() -> limiter.check("login", 2, MINUTE)).isInstanceOf(RateLimitedException.class);
		limiter.reset("login"); // 성공하면 실패 기록을 지운다.
		limiter.check("login", 2, MINUTE);
	}

	@Test
	void rejectedAttemptsDoNotExtendTheBlock() {
		limiter.acquire("k", 1, MINUTE);
		for (int i = 0; i < 5; i++) {
			advance(Duration.ofSeconds(5));
			assertThatThrownBy(() -> limiter.acquire("k", 1, MINUTE)).isInstanceOf(RateLimitedException.class);
		}
		advance(Duration.ofSeconds(35)); // 첫 이벤트로부터 60초
		limiter.acquire("k", 1, MINUTE); // 막힌 시도들이 창을 늘리지 않았다.
	}

	@Test
	void concurrentAcquiresNeverExceedTheLimit() throws Exception {
		RateLimiter real = new RateLimiter();
		java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
		var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
		var start = new java.util.concurrent.CountDownLatch(1);
		var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
		for (int i = 0; i < 200; i++) {
			futures.add(pool.submit(() -> {
				start.await();
				try {
					real.acquire("shared", 50, MINUTE);
					allowed.incrementAndGet();
				} catch (RateLimitedException ignored) {
					// 한도 초과
				}
				return null;
			}));
		}
		start.countDown();
		for (var f : futures) {
			f.get();
		}
		pool.shutdown();
		assertThat(allowed.get()).isEqualTo(50);
	}
}
