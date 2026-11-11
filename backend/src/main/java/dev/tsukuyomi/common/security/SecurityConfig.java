package dev.tsukuyomi.common.security;

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
			.addFilterBefore(sessionAuthFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
			.exceptionHandling(e -> e.authenticationEntryPoint(unauthorizedAsJson(json)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/platform/v1/accounts", "/platform/v1/session").permitAll()
				.requestMatchers("/platform/v1/**").hasAuthority(SessionAuthFilter.ROLE)
				.requestMatchers("/api/v1/**").hasAuthority(ApiKeyFilter.ROLE)
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

	private AuthenticationEntryPoint unauthorizedAsJson(ObjectMapper json) {
		return (request, response, exception) -> {
			response.setStatus(401);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			json.writeValue(response.getOutputStream(), ApiErrorHandler.body("unauthorized",
					request.getRequestURI().startsWith("/api/") ? "missing or invalid apikey header" : "log in first"));
		};
	}

}
