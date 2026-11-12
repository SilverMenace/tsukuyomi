package dev.tsukuyomi.data;

import java.time.Instant;
import java.util.Optional;

import dev.tsukuyomi.auth.JwtCodec;
import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.platform.ProjectRepository;
import dev.tsukuyomi.platform.ProjectRepository.KeyOwner;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantRole;

import org.springframework.stereotype.Component;

/**
 * Turns an API key and an optional end-user access token into a
 * TenantIdentity. This is the single place that decision is made, for HTTP
 * requests (ApiKeyFilter) and for WebSocket connections (LiveSocketHandler).
 *
 *   service key              acts as the service role (a user token is ignored)
 *   anon key, no token       acts as the anon role
 *   anon key + valid token   acts as that user
 *   anon key + bad token     rejected, never downgraded to anonymous
 */
@Component
public class IdentityResolver {

	public enum Status {

		OK, UNKNOWN_KEY, INVALID_TOKEN

	}

	/**
	 * @param identity null unless status is OK
	 * @param expiresAt when the user's token stops being valid; null for the anon and service roles
	 */
	public record Caller(Status status, TenantIdentity identity, Instant expiresAt) {
	}

	private final ProjectRepository projects;

	private final JwtCodec jwt;

	IdentityResolver(ProjectRepository projects, JwtCodec jwt) {
		this.projects = projects;
		this.jwt = jwt;
	}

	/** @param token the access token without any "Bearer " prefix, or null */
	public Caller resolve(String apiKey, String token) {
		Optional<KeyOwner> found = Optional.ofNullable(apiKey)
			.filter(key -> key.startsWith("tsk_") && key.length() <= 100)
			.flatMap(key -> projects.findKeyOwner(Tokens.sha256(key)));
		if (found.isEmpty()) {
			return new Caller(Status.UNKNOWN_KEY, null, null);
		}
		KeyOwner owner = found.get();
		if (owner.role() == TenantRole.SERVICE) {
			return new Caller(Status.OK, TenantIdentity.service(owner.project()), null);
		}
		if (token == null) {
			return new Caller(Status.OK, TenantIdentity.anon(owner.project()), null);
		}
		return jwt.verify(owner.jwtSecret(), owner.project().id(), token, Instant.now())
			.map(claims -> new Caller(Status.OK, TenantIdentity.user(owner.project(), claims.userId()),
					claims.expiresAt()))
			.orElseGet(() -> new Caller(Status.INVALID_TOKEN, null, null));
	}

}
