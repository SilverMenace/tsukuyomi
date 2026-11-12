package dev.tsukuyomi.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import dev.tsukuyomi.auth.EndUserRepository.Credentials;
import dev.tsukuyomi.auth.EndUserRepository.EndUser;
import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.platform.ProjectRepository;
import dev.tsukuyomi.tenancy.ProjectRef;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signup, login and token refresh for the end users of a project.
 *
 * Two tokens are issued:
 *   - an access token (JWT, one hour): sent with every API call, checked
 *     without touching the database;
 *   - a refresh token (random, 30 days, stored hashed): exchanged for a new
 *     pair when the access token expires, and replaced each time it is used.
 * The short access token limits the damage if one leaks; the refresh token
 * keeps the user logged in and can be revoked because it lives in a table.
 */
@Service
public class AuthService {

	public static final Duration ACCESS_LIFETIME = Duration.ofHours(1);

	public static final Duration REFRESH_LIFETIME = Duration.ofDays(30);

	public record UserInfo(UUID id, String email) {
	}

	/** Field names follow the OAuth 2 convention, which client libraries expect. */
	public record TokenResponse(String access_token, String token_type, long expires_in, String refresh_token,
			UserInfo user) {
	}

	private final EndUserRepository users;

	private final ProjectRepository projects;

	private final JwtCodec jwt;

	private final TransactionTemplate transaction;

	private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);

	private final String dummyHash = passwords.encode(Tokens.randomHex(16));

	private final int maxUsers;

	AuthService(EndUserRepository users, ProjectRepository projects, JwtCodec jwt, TransactionTemplate transaction,
			@Value("${tsukuyomi.quota.users-per-project:1000}") int maxUsers) {
		this.users = users;
		this.projects = projects;
		this.jwt = jwt;
		this.transaction = transaction;
		this.maxUsers = maxUsers;
	}

	public TokenResponse signup(ProjectRef project, String rawEmail, String password) {
		String email = normalise(rawEmail);
		String hash = passwords.encode(password);
		UUID userId = transaction.execute(status -> {
			projects.lockProject(project.id()); // serialises signups so the quota cannot be raced
			if (users.count(project.id()) >= maxUsers) {
				throw ApiException.conflict("quota_users", "this project has reached its limit of " + maxUsers + " users");
			}
			return users.insert(project, email, hash)
				.orElseThrow(() -> ApiException.conflict("email_taken", "a user with this email already exists"));
		});
		return issue(project, new UserInfo(userId, email));
	}

	public TokenResponse login(ProjectRef project, String rawEmail, String password) {
		Optional<Credentials> found = users.findByEmail(project.id(), normalise(rawEmail));
		// Same work whether or not the email exists, so timing does not reveal which.
		boolean matches = passwords.matches(password, found.map(Credentials::passwordHash).orElse(dummyHash));
		if (found.isEmpty() || !matches) {
			throw ApiException.unauthorized("wrong email or password");
		}
		return issue(project, new UserInfo(found.get().userId(), found.get().email()));
	}

	/** Exchanges a refresh token for a new pair. The old refresh token stops working. */
	public TokenResponse refresh(ProjectRef project, String refreshToken) {
		UUID userId = users.consumeSession(Tokens.sha256(refreshToken), project.id())
			.orElseThrow(() -> ApiException.unauthorized("invalid or expired refresh token"));
		EndUser user = users.findById(project.id(), userId)
			.orElseThrow(() -> ApiException.unauthorized("invalid or expired refresh token"));
		return issue(project, new UserInfo(user.id(), user.email()));
	}

	public void logout(ProjectRef project, String refreshToken) {
		users.consumeSession(Tokens.sha256(refreshToken), project.id());
	}

	public Optional<UserInfo> find(ProjectRef project, UUID userId) {
		return users.findById(project.id(), userId).map(u -> new UserInfo(u.id(), u.email()));
	}

	public List<EndUser> list(ProjectRef project) {
		return users.list(project.id(), 200);
	}

	private TokenResponse issue(ProjectRef project, UserInfo user) {
		byte[] secret = projects.findJwtSecret(project.id());
		Instant now = Instant.now();
		String access = jwt.sign(secret, new JwtCodec.Claims(user.id(), project.id(), now.plus(ACCESS_LIFETIME)));
		String refresh = Tokens.randomHex(32);
		users.deleteExpiredSessions(project.id());
		users.insertSession(Tokens.sha256(refresh), project.id(), user.id(), now.plus(REFRESH_LIFETIME));
		return new TokenResponse(access, "bearer", ACCESS_LIFETIME.toSeconds(), refresh, user);
	}

	private static String normalise(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

}
