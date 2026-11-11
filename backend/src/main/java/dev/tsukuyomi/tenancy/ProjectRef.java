package dev.tsukuyomi.tenancy;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The minimum the data plane needs to know about a project: its id and the
 * Postgres schema its tables live in.
 *
 * The schema name ends up inside SQL text (identifiers cannot be bind
 * parameters), so the constructor refuses anything that is not exactly the
 * shape we generate. An object of this type is therefore always safe to embed.
 */
public record ProjectRef(UUID id, String schemaName) {

	private static final Pattern SCHEMA = Pattern.compile("^p_[0-9a-f]{12}$");

	public ProjectRef {
		if (id == null || schemaName == null || !SCHEMA.matcher(schemaName).matches()) {
			throw new IllegalArgumentException("not a project schema name");
		}
	}

	/** The Postgres role a request acts as, e.g. p_3fa9c1d2e4b7_service. */
	public String roleName(TenantRole role) {
		return schemaName + "_" + role.suffix();
	}

}
