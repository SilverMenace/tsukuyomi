package dev.tsukuyomi.common.limit;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import dev.tsukuyomi.common.ApiErrorHandler;
import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.common.limit.RateLimiter.Limit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Refuses callers that send too much, before any other work is done for them
 * (it runs ahead of the security filters, so a refused request costs no
 * database query and no password hash).
 *
 * Three limits, each a token bucket (see TokenBucket):
 *
 *   per address, credentials   signup and login, for accounts and end users.
 *                              Each attempt costs a slow password hash, and
 *                              this is where passwords get guessed.
 *   per address, everything    one machine cannot take the whole server.
 *   per API key                one project cannot starve the others, however
 *                              many machines its users have.
 *
 * Static files and the health check are not counted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Set<String> CREDENTIAL_PATHS = Set.of("/auth/v1/signup", "/auth/v1/login",
			"/platform/v1/accounts", "/platform/v1/session");

	private final RateLimiter limiter = new RateLimiter();

	private final ClientAddress addresses;

	private final ObjectMapper json;

	private final boolean enabled;

	private final Limit perAddress;

	private final Limit credentials;

	private final Limit perKey;

	private final long maxBodyBytes;

	RateLimitFilter(ClientAddress addresses, ObjectMapper json,
			@Value("${tsukuyomi.rate-limit.enabled:true}") boolean enabled,
			@Value("${tsukuyomi.rate-limit.address-per-second:20}") double addressPerSecond,
			@Value("${tsukuyomi.rate-limit.address-burst:100}") double addressBurst,
			@Value("${tsukuyomi.rate-limit.credentials-per-minute:10}") double credentialsPerMinute,
			@Value("${tsukuyomi.rate-limit.credentials-burst:10}") double credentialsBurst,
			@Value("${tsukuyomi.rate-limit.key-per-second:50}") double keyPerSecond,
			@Value("${tsukuyomi.rate-limit.key-burst:200}") double keyBurst,
			@Value("${tsukuyomi.max-body-bytes:1048576}") long maxBodyBytes) {
		this.addresses = addresses;
		this.json = json;
		this.enabled = enabled;
		this.perAddress = new Limit(addressBurst, addressPerSecond);
		this.credentials = new Limit(credentialsBurst, credentialsPerMinute / 60);
		this.perKey = new Limit(keyBurst, keyPerSecond);
		this.maxBodyBytes = maxBodyBytes;
	}

	/**
	 * The path as the router will see it: percent-decoded, without ";params".
	 * The raw request URI must not be used for decisions here. "/auth/v1/lo%67in"
	 * is not equal to "/auth/v1/login" as text, but Spring decodes it and routes
	 * it to the login handler, so a check on the raw text could be walked around.
	 */
	private static String path(HttpServletRequest request) {
		return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = path(request);
		boolean counted = path.startsWith("/api/") || path.startsWith("/auth/") || path.startsWith("/platform/")
				|| path.startsWith("/realtime/");
		// A browser's CORS preflight (OPTIONS) carries no key and does no work.
		return !enabled || !counted || "OPTIONS".equals(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBodyBytes) {
			refuse(request, response, 413, "too_large", "the request body is larger than " + maxBodyBytes + " bytes", 0);
			return;
		}
		// A body can also arrive in chunks with no declared length, so the
		// declared length alone proves nothing: count what is actually read.
		request = new LimitedBodyRequest(request, maxBodyBytes);
		String address = addresses.of(request);
		double wait = 0;
		if ("POST".equals(request.getMethod()) && CREDENTIAL_PATHS.contains(path(request))) {
			wait = limiter.tryAcquire("credentials:" + address, credentials);
		}
		if (wait == 0) {
			wait = limiter.tryAcquire("address:" + address, perAddress);
		}
		String apiKey = request.getHeader("apikey");
		if (wait == 0 && apiKey != null) {
			// Hashed so the limiter's memory never holds a usable key.
			wait = limiter.tryAcquire("key:" + Tokens.sha256(apiKey.length() > 100 ? apiKey.substring(0, 100) : apiKey),
					perKey);
		}
		if (wait > 0) {
			// Saying which address was counted lets a caller behind a shared
			// address understand why, and it is only ever their own.
			refuse(request, response, 429, "rate_limited",
					String.format(Locale.ROOT, "too many requests from %s; try again in %d s", address,
							(long) Math.ceil(wait)),
					(long) Math.ceil(wait));
			return;
		}
		chain.doFilter(request, response);
	}

	private void refuse(HttpServletRequest request, HttpServletResponse response, int status, String code,
			String message, long retryAfterSeconds) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		if (retryAfterSeconds > 0) {
			response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
		}
		String path = path(request);
		if (path.startsWith("/api/") || path.startsWith("/auth/")) {
			// This filter runs before the CORS filter. Without this header a
			// browser app on another site would see a network error, not our message.
			response.setHeader("Access-Control-Allow-Origin", "*");
		}
		json.writeValue(response.getOutputStream(), ApiErrorHandler.body(code, message));
	}

	@Scheduled(fixedDelay = 60_000)
	void forgetIdleCallers() {
		limiter.removeIdle();
	}

}
