package dev.tsukuyomi.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.common.Tokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** Signup, login and sessions for dashboard accounts. */
@Service
public class AccountService {

	public static final Duration SESSION_LIFETIME = Duration.ofDays(7);

	/**
	 * A valid BCrypt hash of a random string. When the email is unknown we still
	 * run one BCrypt check against this, so "wrong email" and "wrong password"
	 * take the same time and an attacker cannot tell which emails are registered.
	 */
	private final String dummyHash;

	private final AccountRepository accounts;

	private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);

	private final boolean signupEnabled;

	AccountService(AccountRepository accounts, @Value("${tsukuyomi.signup.enabled:true}") boolean signupEnabled) {
		this.accounts = accounts;
		this.signupEnabled = signupEnabled;
		this.dummyHash = passwords.encode(Tokens.randomHex(16));
	}

	public Account signup(String email, String password) {
		if (!signupEnabled) {
			throw ApiException.forbidden("signup is closed on this instance");
		}
		String normalised = email.trim().toLowerCase(Locale.ROOT);
		return accounts.insert(normalised, passwords.encode(password))
			.orElseThrow(() -> ApiException.conflict("email_taken", "an account with this email already exists"));
	}

	/** @return a new session token for the browser cookie */
	public String login(String email, String password) {
		String normalised = email.trim().toLowerCase(Locale.ROOT);
		Optional<AccountRepository.Credentials> found = accounts.findCredentials(normalised);
		String hash = found.map(AccountRepository.Credentials::passwordHash).orElse(dummyHash);
		boolean matches = passwords.matches(password, hash);
		if (found.isEmpty() || !matches) {
			throw ApiException.unauthorized("wrong email or password");
		}
		return startSession(found.get().account());
	}

	public String startSession(Account account) {
		String token = Tokens.randomHex(32);
		accounts.insertSession(Tokens.sha256(token), account.id(), Instant.now().plus(SESSION_LIFETIME));
		return token;
	}

	public Optional<Account> findBySessionToken(String token) {
		return accounts.findBySession(Tokens.sha256(token));
	}

	public void logout(String token) {
		accounts.deleteSession(Tokens.sha256(token));
		accounts.deleteExpiredSessions();
	}

}
