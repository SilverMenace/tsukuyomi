package dev.tsukuyomi.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Everything Postgres needs to decide what a request may see:
 * which project, which role, and (for logged-in end users) which user.
 */
public record TenantIdentity(ProjectRef project, TenantRole role, UUID userId) {

	public TenantIdentity {
		Objects.requireNonNull(project);
		Objects.requireNonNull(role);
		if ((role == TenantRole.USER) != (userId != null)) {
			throw new IllegalArgumentException("exactly the USER role carries a user id");
		}
	}

	public static TenantIdentity service(ProjectRef project) {
		return new TenantIdentity(project, TenantRole.SERVICE, null);
	}

	public static TenantIdentity anon(ProjectRef project) {
		return new TenantIdentity(project, TenantRole.ANON, null);
	}

	public static TenantIdentity user(ProjectRef project, UUID userId) {
		return new TenantIdentity(project, TenantRole.USER, userId);
	}

}
