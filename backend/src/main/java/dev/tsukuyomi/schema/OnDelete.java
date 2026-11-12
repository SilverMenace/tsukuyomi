package dev.tsukuyomi.schema;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.tsukuyomi.common.ApiException;

/** What happens to rows pointing at a row that gets deleted. */
public enum OnDelete {

	/** Refuse to delete a row that others still point at. */
	RESTRICT("restrict", "restrict"),

	/** Delete the rows that point at it too. */
	CASCADE("cascade", "cascade"),

	/** Keep them, but empty the reference. */
	SET_NULL("set_null", "set null");

	private final String json;

	private final String sql;

	OnDelete(String json, String sql) {
		this.json = json;
		this.sql = sql;
	}

	@JsonValue
	public String json() {
		return json;
	}

	public String sql() {
		return sql;
	}

	@JsonCreator
	public static OnDelete parse(String value) {
		String wanted = value == null ? "" : value.toLowerCase(Locale.ROOT);
		return Arrays.stream(values())
			.filter(o -> o.json.equals(wanted))
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("invalid_on_delete",
					"onDelete must be restrict, cascade or set_null"));
	}

}
