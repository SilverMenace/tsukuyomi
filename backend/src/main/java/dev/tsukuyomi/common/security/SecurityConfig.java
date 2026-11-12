package dev.tsukuyomi.common.security;

import java.util.List;

import dev.tsukuyomi.common.ApiErrorHandler;
import dev.tsukuyomi.data.ApiKeyFilter;
import dev.tsukuyomi.platform.SessionAuthFilter;

import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security: deny by default.
 *
 * Every route is closed unless it is explicitly opened here. New features add
 * their own rules as they are built, so forgetting to configure a route makes
 * it unreachable rather than public.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SessionAuthFilter sessionAuthFilter,
			ApiKeyFilter apiKeyFilter, ObjectMapper json) throws Exception {
		http
			// We keep our own session table; Spring must not create HTTP sessions.
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// See DECISIONS.md D9 for why the built-in CSRF tokens are off.
			.csrf(AbstractHttpConfigurer::disable)
			.cors(cors -> cors.configurationSource(corsForPublicApi()))
			.addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
			.exceptionHandling(e -> e.authenticationEntryPoint(unauthorizedAsJson(json)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				// The dashboard's static files (built by Vite into the jar).
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/platform/v1/accounts", "/platform/v1/session").permitAll()
				.requestMatchers("/platform/v1/**").hasAuthority(SessionAuthFilter.ROLE)
				.requestMatchers("/api/v1/**", "/auth/v1/**").hasAuthority(ApiKeyFilter.ROLE)
				// The WebSocket handshake is open; the connection authenticates itself
				// with its first message (see LiveSocketHandler).
				.requestMatchers(HttpMethod.GET, "/realtime/v1").permitAll()
				.anyRequest().denyAll());
		return http.build();
	}

	/**
	 * Our filters are Spring beans, so Spring Boot would also register them as
	 * plain servlet filters and run them twice. They should run only inside the
	 * security chain above.
	 */
	@Bean
	FilterRegistrationBean<SessionAuthFilter> sessionAuthFilterRegistration(SessionAuthFilter filter) {
		FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(ApiKeyFilter filter) {
		FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/**
	 * Browsers block a page on one site from calling another site unless that
	 * site opts in (CORS). Apps built on a project run on their own domains, so
	 * the public API must opt in for every origin. That is safe here because
	 * these endpoints never use cookies: a foreign page cannot ride on a
	 * visitor's login, it can only send keys and tokens it already holds.
	 * The dashboard's /platform endpoints use a cookie and stay same-origin only.
	 */
	private static CorsConfigurationSource corsForPublicApi() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of("*"));
		config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
		config.setAllowedHeaders(List.of("apikey", "authorization", "content-type", "prefer"));
		config.setExposedHeaders(List.of("X-Total-Count"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		source.registerCorsConfiguration("/auth/**", config);
		return source;
	}

	private AuthenticationEntryPoint unauthorizedAsJson(ObjectMapper json) {
		return (request, response, exception) -> {
			response.setStatus(401);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			json.writeValue(response.getOutputStream(), ApiErrorHandler.body("unauthorized",
					request.getRequestURI().startsWith("/api/") ? "missing or invalid apikey header" : "log in first"));
		};
	}

}
