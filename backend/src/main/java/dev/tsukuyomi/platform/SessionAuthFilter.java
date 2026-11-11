package dev.tsukuyomi.platform;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every /platform request. If the request carries a valid session
 * cookie, it records "this request is made by account X" for the rest of the
 * request. If not, it does nothing, and the security rules reject the request
 * later because nobody is logged in.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

	public static final String ROLE = "ROLE_ACCOUNT";

	private final AccountService accounts;

	private final SessionCookie cookie;

	SessionAuthFilter(AccountService accounts, SessionCookie cookie) {
		this.accounts = accounts;
		this.cookie = cookie;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/platform/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		cookie.read(request).flatMap(accounts::findBySessionToken).ifPresent(account -> {
			var authentication = new UsernamePasswordAuthenticationToken(account, null,
					List.of(new SimpleGrantedAuthority(ROLE)));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		});
		chain.doFilter(request, response);
	}

}
