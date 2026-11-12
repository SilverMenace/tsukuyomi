package dev.tsukuyomi.schema;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.tsukuyomi.common.ApiException;

/** Who a rule lets through. */
public enum RuleKind {

	/** Nobody except the service key. The default for every operation. */
	NOBODY,

	/** Anyone holding the anon key, logged in or not. */
	ANYONE,

	/** Any logged-in end user. */
	AUTHENTICATED,

	/** Only the logged-in user whose id is stored in a chosen column of the row. */
	OWNER;

	@JsonValue
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static RuleKind parse(String value) {
		return Arrays.stream(values())
			.filter(k -> k.json().equals(value))
			.findFirst()
			.orElseThrow(() -> ApiException.badRequest("invalid_rule",
					"rule must be nobody, anyone, authenticated or owner"));
	}

}
