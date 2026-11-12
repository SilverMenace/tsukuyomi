package dev.tsukuyomi.common.limit;

/**
 * The classic rate-limiting algorithm. Picture a bucket that holds at most
 * {@code capacity} tokens and gains {@code refillPerSecond} tokens every
 * second. Each request takes one token; an empty bucket means "too fast".
 *
 * So a caller may burst up to {@code capacity} requests at once, but over
 * time cannot exceed {@code refillPerSecond}. Tokens are not added by a
 * timer: each call works out how many were earned since the last call.
 */
final class TokenBucket {

	private final double capacity;

	private final double refillPerSecond;

	private double tokens;

	private long lastNanos;

	TokenBucket(double capacity, double refillPerSecond, long nowNanos) {
		this.capacity = capacity;
		this.refillPerSecond = refillPerSecond;
		this.tokens = capacity;
		this.lastNanos = nowNanos;
	}

	/** @return 0 if a token was taken, otherwise the seconds to wait until one is available */
	synchronized double tryTake(long nowNanos) {
		refill(nowNanos);
		if (tokens >= 1) {
			tokens -= 1;
			return 0;
		}
		return (1 - tokens) / refillPerSecond;
	}

	/** True when nothing has been taken for long enough that the bucket is full again. */
	synchronized boolean isFull(long nowNanos) {
		refill(nowNanos);
		return tokens >= capacity;
	}

	private void refill(long nowNanos) {
		double earned = (nowNanos - lastNanos) / 1e9 * refillPerSecond;
		tokens = Math.min(capacity, tokens + earned);
		lastNanos = nowNanos;
	}

}
