package dev.tsukuyomi.common.limit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One token bucket per key (an address, an API key, ...), kept in memory.
 *
 * In memory means each server instance counts on its own, and counts start
 * again after a restart. For one instance that is exactly right and needs no
 * extra service; several instances would share the counts in something like Redis.
 */
public final class RateLimiter {

	/** A burst size and a steady rate. */
	public record Limit(double burst, double perSecond) {
	}

	/** If an attacker invents this many different keys, forget everything rather than run out of memory. */
	private static final int MAX_KEYS = 50_000;

	private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

	private final LongSupplier nanoClock;

	public RateLimiter() {
		this(System::nanoTime);
	}

	RateLimiter(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	/** @return 0 if the request may proceed, otherwise how many seconds the caller should wait */
	public double tryAcquire(String key, Limit limit) {
		long now = nanoClock.getAsLong();
		if (buckets.size() >= MAX_KEYS) {
			removeIdle();
			if (buckets.size() >= MAX_KEYS) {
				buckets.clear();
			}
		}
		return buckets.computeIfAbsent(key, k -> new TokenBucket(limit.burst(), limit.perSecond(), now)).tryTake(now);
	}

	/** A full bucket behaves exactly like a new one, so it can be dropped. */
	public void removeIdle() {
		long now = nanoClock.getAsLong();
		buckets.values().removeIf(bucket -> bucket.isFull(now));
	}

	int size() {
		return buckets.size();
	}

}
