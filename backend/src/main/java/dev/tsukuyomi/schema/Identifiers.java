package dev.tsukuyomi.schema;

import java.util.Set;
import java.util.regex.Pattern;

import dev.tsukuyomi.common.ApiException;

/**
 * Rules for table and column names chosen by users.
 *
 * Names become SQL identifiers, and identifiers cannot be sent as bind
 * parameters. So instead of trying to escape arbitrary text, we only accept a
 * very small alphabet: a lowercase letter followed by lowercase letters, digits
 * or underscores. A name that passes cannot contain a quote, a space, a
 * semicolon or anything else that has meaning in SQL.
 */
public final class Identifiers {

	/**
	 * Max 28 characters: a generated constraint name is table + "_" + column + "_key",
	 * and 28 + 1 + 28 + 4 = 61 stays under Postgres's 63-character limit.
	 */
	private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]{0,27}$");

	/** Columns every table gets automatically. */
	public static final Set<String> SYSTEM_COLUMNS = Set.of("id", "created_at");

	/** Words the data API uses as query parameters; a column with such a name could not be filtered on. */
	private static final Set<String> RESERVED_COLUMNS = Set.of("select", "order", "limit", "offset");

	/** Table names we keep for built-in features. */
	private static final Set<String> RESERVED_TABLES = Set.of("users");

	private Identifiers() {
	}

	public static boolean isValid(String name) {
		return name != null && VALID.matcher(name).matches();
	}

	public static void requireValidTable(String name) {
		requireValid(name, "table");
		if (RESERVED_TABLES.contains(name) || name.startsWith("tsk_") || name.startsWith("pg_")) {
			throw ApiException.badRequest("reserved_name", "table name '" + name + "' is reserved");
		}
	}

	public static void requireValidColumn(String name) {
		requireValid(name, "column");
		if (SYSTEM_COLUMNS.contains(name)) {
			throw ApiException.badRequest("reserved_name", "column '" + name + "' exists on every table already");
		}
		if (RESERVED_COLUMNS.contains(name)) {
			throw ApiException.badRequest("reserved_name", "column name '" + name + "' is reserved by the API");
		}
	}

	private static void requireValid(String name, String what) {
		if (!isValid(name)) {
			throw ApiException.badRequest("invalid_name", what
					+ " names must start with a lowercase letter and use only lowercase letters, digits and _ (max 28)");
		}
	}

	/**
	 * Wraps a name in double quotes for use in SQL. Re-checks the pattern, so
	 * even a programming mistake elsewhere cannot push unsafe text through here.
	 */
	public static String quote(String name) {
		if (!isValid(name)) {
			throw new IllegalArgumentException("refusing to quote an invalid identifier");
		}
		return "\"" + name + "\"";
	}

}
