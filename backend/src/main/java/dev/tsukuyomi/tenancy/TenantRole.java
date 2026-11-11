package dev.tsukuyomi.tenancy;

/**
 * Who a data request acts as inside a project. Each project has one Postgres
 * role per value.
 */
public enum TenantRole {

	/** Someone holding only the public anon key, not logged in. */
	ANON("anon"),

	/** A logged-in end user of the project. */
	USER("user"),

	/** The secret service key: full access to the project's own tables. */
	SERVICE("service");

	private final String suffix;

	TenantRole(String suffix) {
		this.suffix = suffix;
	}

	public String suffix() {
		return suffix;
	}

}
