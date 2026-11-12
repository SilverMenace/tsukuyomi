package dev.tsukuyomi.data;

import java.io.IOException;
import java.util.List;

import dev.tsukuyomi.common.ApiErrorHandler;
import dev.tsukuyomi.tenancy.TenantIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every /api and /auth request and answers one question: which
 * project is this, and who is the caller inside it?
 *
 * The answer (a TenantIdentity) is the ONLY thing that decides which Postgres
 * role the request will run as. It is derived from two headers and nothing
 * else: the API key (which project, anon or service) and, optionally, an end
 * user's access token. Path, query and body cannot influence it. The decision
 * itself is made by IdentityResolver.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

	public static final String ROLE = "ROLE_API";

	public static final String HEADER = "apikey";

	private static final String BEARER = "Bearer ";

	private final IdentityResolver identities;

	private final ObjectMapper json;

	ApiKeyFilter(IdentityResolver identities, ObjectMapper json) {
		this.identities = identities;
		this.json = json;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.startsWith("/api/") && !path.startsWith("/auth/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		String token = authorization != null && authorization.startsWith(BEARER)
				? authorization.substring(BEARER.length()) : null;
		IdentityResolver.Caller caller = identities.resolve(request.getHeader(HEADER), token);
		if (caller.status() == IdentityResolver.Status.UNKNOWN_KEY) {
			chain.doFilter(request, response); // not authenticated: the security rules answer 401
			return;
		}
		if (caller.status() == IdentityResolver.Status.INVALID_TOKEN) {
			// A token was sent but is not valid. Falling back to "anonymous" here
			// would hide the problem from the client, so say so.
			response.setStatus(401);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			json.writeValue(response.getOutputStream(),
					ApiErrorHandler.body("invalid_token", "the access token is invalid or has expired"));
			return;
		}
		TenantIdentity identity = caller.identity();
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null,
					List.of(new SimpleGrantedAuthority(ROLE))));
		chain.doFilter(request, response);
	}

}
