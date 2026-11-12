package dev.tsukuyomi.schema;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.tsukuyomi.common.ApiException;

/** The four things a rule can govern. The name is also the SQL keyword. */
public enum RuleOperation {

	SELECT, INSERT, UPDATE, DELETE;

	@JsonValue
	public String sql() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static RuleOperation parse(String value) {
		return Arrays.stream(values())
			.filter(o -> o.sql().equals(value))
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("invalid_rule",
					"operation must be select, insert, update or delete"));
	}

}
