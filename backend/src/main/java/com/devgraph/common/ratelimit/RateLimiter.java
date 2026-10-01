package com.devgraph.common.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/**
 * 설계서 §17.6 단일 인스턴스 in-memory 슬라이딩 윈도 limiter. 키별로 최근 이벤트 시각을 들고 있다가 창 밖의 것을 버린다.
 * ponytail: 인스턴스가 둘 이상이 되면 인스턴스마다 따로 센다(실제 한도가 인스턴스 수만큼 커진다) — 그때 Redis 등 공유 저장소로 옮긴다.
 */
@Component
public class RateLimiter {

	private static final int CLEANUP_THRESHOLD = 10_000;

	private final Map<String, ArrayDeque<Long>> events = new ConcurrentHashMap<>();
	private final LongSupplier nanoClock;

	public RateLimiter() {
		this(System::nanoTime);
	}

	RateLimiter(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	/** 이미 한도에 닿았으면 던진다(이벤트를 기록하지 않는다). 실패만 세는 용도(로그인, 비밀번호 추측)에 쓴다. */
	public void check(String key, int max, Duration window) {
		ArrayDeque<Long> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (deque) {
			long now = nanoClock.getAsLong();
			prune(deque, now, window);
			if (deque.size() >= max) {
				throw new RateLimitedException(retryAfter(deque, now, window));
			}
		}
	}

	/** 이벤트 하나를 기록한다. */
	public void hit(String key, Duration window) {
		ArrayDeque<Long> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (deque) {
			long now = nanoClock.getAsLong();
			prune(deque, now, window);
			deque.addLast(now);
		}
		if (events.size() > CLEANUP_THRESHOLD) {
			cleanup(window);
		}
	}

	/** 검사와 기록을 한 번에(원자적으로). 모든 요청을 세는 용도(검색, 변경, Export)에 쓴다. */
	public void acquire(String key, int max, Duration window) {
		ArrayDeque<Long> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (deque) {
			long now = nanoClock.getAsLong();
			prune(deque, now, window);
			if (deque.size() >= max) {
				throw new RateLimitedException(retryAfter(deque, now, window));
			}
			deque.addLast(now);
		}
		if (events.size() > CLEANUP_THRESHOLD) {
			cleanup(window);
		}
	}

	/** 성공하면 실패 기록을 지운다(정상 로그인이 이후 실패 횟수에 영향을 주지 않도록). */
	public void reset(String key) {
		events.remove(key);
	}

	private static void prune(ArrayDeque<Long> deque, long now, Duration window) {
		long cutoff = now - window.toNanos();
		while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
			deque.pollFirst();
		}
	}

	private static long retryAfter(ArrayDeque<Long> deque, long now, Duration window) {
		long oldest = deque.peekFirst();
		long waitNanos = oldest + window.toNanos() - now;
		return Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
	}

	/** 창이 지난 키를 정리해 메모리가 무한히 늘지 않게 한다. */
	private void cleanup(Duration window) {
		long now = nanoClock.getAsLong();
		for (Iterator<Map.Entry<String, ArrayDeque<Long>>> it = events.entrySet().iterator(); it.hasNext();) {
			ArrayDeque<Long> deque = it.next().getValue();
			synchronized (deque) {
				prune(deque, now, window);
				if (deque.isEmpty()) {
					it.remove();
				}
			}
		}
	}
}
