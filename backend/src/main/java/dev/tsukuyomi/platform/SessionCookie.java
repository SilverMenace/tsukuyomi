package dev.tsukuyomi.platform;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The dashboard's session cookie.
 *
 * HttpOnly: JavaScript cannot read it, so a script-injection bug cannot steal it.
 * Secure: only sent over HTTPS.
 * SameSite=Strict: the browser never attaches it to a request started by another
 * site, which is our defence against cross-site request forgery (see DECISIONS.md).
 */
@Component
public class SessionCookie {

	public static final String NAME = "tsk_session";

	private final boolean secure;

	SessionCookie(@Value("${tsukuyomi.cookie.secure:true}") boolean secure) {
		this.secure = secure;
	}

	public ResponseCookie issue(String token) {
		return build(token, AccountService.SESSION_LIFETIME);
	}

	public ResponseCookie clear() {
		return build("", Duration.ZERO);
	}

	public Optional<String> read(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return Optional.empty();
		}
		return Arrays.stream(request.getCookies())
			.filter(c -> NAME.equals(c.getName()))
			.map(Cookie::getValue)
			.filter(v -> !v.isBlank())
			.findFirst();
	}

	private ResponseCookie build(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Strict")
			.path("/")
			.maxAge(maxAge)
			.build();
	}

}
