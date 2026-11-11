package dev.tsukuyomi.data;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.platform.ProjectRepository;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantRole;

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
 * Runs before every /api request and answers one question: which project is
 * this, and who is the caller inside it?
 *
 * The answer (a TenantIdentity) is the ONLY thing that decides which Postgres
 * role the request will run as. It is derived from the API key alone; nothing
 * else in the request (path, query, body) can influence it.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

	public static final String ROLE = "ROLE_API";

	public static final String HEADER = "apikey";

	private final ProjectRepository projects;

	ApiKeyFilter(ProjectRepository projects) {
		this.projects = projects;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional.ofNullable(request.getHeader(HEADER))
			.filter(key -> key.startsWith("tsk_") && key.length() <= 100)
			.flatMap(key -> projects.findKeyOwner(Tokens.sha256(key)))
			.ifPresent(owner -> {
				TenantIdentity identity = owner.role() == TenantRole.SERVICE ? TenantIdentity.service(owner.project())
						: TenantIdentity.anon(owner.project());
				SecurityContextHolder.getContext()
					.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null,
							List.of(new SimpleGrantedAuthority(ROLE))));
			});
		chain.doFilter(request, response);
	}

}
