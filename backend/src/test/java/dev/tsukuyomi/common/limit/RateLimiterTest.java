package dev.tsukuyomi.common.limit;

import java.util.concurrent.atomic.AtomicLong;

import dev.tsukuyomi.common.limit.RateLimiter.Limit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure logic with a clock the test controls, so no sleeping and no flakiness. */
class RateLimiterTest {

	private final AtomicLong nanos = new AtomicLong();

	private final RateLimiter limiter = new RateLimiter(nanos::get);

	private void advanceSeconds(double seconds) {
		nanos.addAndGet((long) (seconds * 1e9));
	}

	@Test
	void aBurstIsAllowedThenTheSteadyRateApplies() {
		Limit limit = new Limit(3, 2); // 3 at once, then 2 per second
		assertThat(limiter.tryAcquire("a", limit)).isZero();
		assertThat(limiter.tryAcquire("a", limit)).isZero();
		assertThat(limiter.tryAcquire("a", limit)).isZero();
		// Empty: the answer is how long until the next token (half a second at 2 per second).
		assertThat(limiter.tryAcquire("a", limit)).isCloseTo(0.5, within(1e-6));

		advanceSeconds(0.5);
		assertThat(limiter.tryAcquire("a", limit)).isZero();
		assertThat(limiter.tryAcquire("a", limit)).isPositive();
	}

	@Test
	void waitingLongNeverSavesMoreThanTheBurst() {
		Limit limit = new Limit(3, 2);
		advanceSeconds(3600);
		int allowed = 0;
		for (int i = 0; i < 10; i++) {
			allowed += limiter.tryAcquire("a", limit) == 0 ? 1 : 0;
		}
		assertThat(allowed).isEqualTo(3);
	}

	@Test
	void keysDoNotShareTokens() {
		Limit limit = new Limit(1, 1);
		assertThat(limiter.tryAcquire("a", limit)).isZero();
		assertThat(limiter.tryAcquire("a", limit)).isPositive();
		assertThat(limiter.tryAcquire("b", limit)).isZero();
	}

	@Test
	void idleCallersAreForgotten() {
		Limit limit = new Limit(2, 1);
		limiter.tryAcquire("a", limit);
		limiter.tryAcquire("b", limit);
		limiter.removeIdle();
		assertThat(limiter.size()).isEqualTo(2); // both still owe a token

		advanceSeconds(1);
		limiter.removeIdle();
		assertThat(limiter.size()).isZero();
	}

	@Test
	void theVisitorIsCountedFromTheRightOfForwardedFor() {
		ClientAddress direct = new ClientAddress(0);
		ClientAddress oneProxy = new ClientAddress(1);
		ClientAddress twoProxies = new ClientAddress(2);

		// No proxy configured: the header is ignored completely.
		assertThat(direct.resolve("1.2.3.4", "9.9.9.9")).isEqualTo("9.9.9.9");
		assertThat(oneProxy.resolve("203.0.113.7", "10.0.0.1")).isEqualTo("203.0.113.7");
		// The client put a fake address in front; the proxy appended the real one.
		assertThat(oneProxy.resolve("6.6.6.6, 203.0.113.7", "10.0.0.1")).isEqualTo("203.0.113.7");
		assertThat(twoProxies.resolve("6.6.6.6, 203.0.113.7, 172.16.0.5", "10.0.0.1")).isEqualTo("203.0.113.7");
		// Fewer entries than proxies, no header, or rubbish: fall back to the socket.
		assertThat(twoProxies.resolve("203.0.113.7", "10.0.0.1")).isEqualTo("10.0.0.1");
		assertThat(oneProxy.resolve(null, "10.0.0.1")).isEqualTo("10.0.0.1");
		assertThat(oneProxy.resolve("x".repeat(200), "10.0.0.1")).isEqualTo("10.0.0.1");
	}

}
