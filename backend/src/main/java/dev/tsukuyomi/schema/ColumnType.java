package dev.tsukuyomi.schema;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.tsukuyomi.common.ApiException;

/**
 * The column types a user can pick. A fixed list: the SQL type name always
 * comes from this enum, never from request text.
 */
public enum ColumnType {

	TEXT("text"), INTEGER("integer"), BIGINT("bigint"), NUMERIC("numeric"), BOOLEAN("boolean"),
	TIMESTAMPTZ("timestamptz"), DATE("date"), UUID("uuid"), JSONB("jsonb");

	private final String sql;

	ColumnType(String sql) {
		this.sql = sql;
	}

	/** The Postgres type name, which is also the name used in JSON. */
	@JsonValue
	public String sql() {
		return sql;
	}

	@JsonCreator
	public static ColumnType parse(String value) {
		String wanted = value == null ? "" : value.toLowerCase(Locale.ROOT);
		return Arrays.stream(values())
			.filter(t -> t.sql.equals(wanted))
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("invalid_type", "unknown column type '" + value + "'"));
	}

}
