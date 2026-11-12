package dev.tsukuyomi.data;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import dev.tsukuyomi.auth.JwtCodec;
import dev.tsukuyomi.common.ApiErrorHandler;
import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.platform.ProjectRepository;
import dev.tsukuyomi.platform.ProjectRepository.KeyOwner;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantRole;

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
 * user's access token. Path, query and body cannot influence it.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

	public static final String ROLE = "ROLE_API";

	public static final String HEADER = "apikey";

	private static final String BEARER = "Bearer ";

	private final ProjectRepository projects;

	private final JwtCodec jwt;

	private final ObjectMapper json;

	ApiKeyFilter(ProjectRepository projects, JwtCodec jwt, ObjectMapper json) {
		this.projects = projects;
		this.jwt = jwt;
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
		Optional<KeyOwner> owner = Optional.ofNullable(request.getHeader(HEADER))
			.filter(key -> key.startsWith("tsk_") && key.length() <= 100)
			.flatMap(key -> projects.findKeyOwner(Tokens.sha256(key)));
		if (owner.isEmpty()) {
			chain.doFilter(request, response); // not authenticated: the security rules answer 401
			return;
		}
		TenantIdentity identity = identify(owner.get(), request.getHeader("Authorization"));
		if (identity == null) {
			// A token was sent but is not valid. Falling back to "anonymous" here
			// would hide the problem from the client, so say so.
			response.setStatus(401);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			json.writeValue(response.getOutputStream(),
					ApiErrorHandler.body("invalid_token", "the access token is invalid or has expired"));
			return;
		}
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null,
					List.of(new SimpleGrantedAuthority(ROLE))));
		chain.doFilter(request, response);
	}

	/**
	 * service key              acts as the service role (a user token is ignored)
	 * anon key, no token       acts as the anon role
	 * anon key + valid token   acts as that user
	 * anon key + bad token     null: reject
	 */
	private TenantIdentity identify(KeyOwner owner, String authorization) {
		if (owner.role() == TenantRole.SERVICE) {
			return TenantIdentity.service(owner.project());
		}
		if (authorization == null || !authorization.startsWith(BEARER)) {
			return TenantIdentity.anon(owner.project());
		}
		return jwt.verify(owner.jwtSecret(), owner.project().id(), authorization.substring(BEARER.length()), Instant.now())
			.map(claims -> TenantIdentity.user(owner.project(), claims.userId()))
			.orElse(null);
	}

}
