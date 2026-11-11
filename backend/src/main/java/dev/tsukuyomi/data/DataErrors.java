package dev.tsukuyomi.data;

import java.sql.SQLException;

import dev.tsukuyomi.common.ApiException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

/**
 * Translates a database error into an API error.
 *
 * Postgres labels every error with a five-character SQLSTATE code. The first
 * two characters are the class, which is enough to pick an HTTP status. We
 * pass on Postgres's own first line for errors the caller caused (bad value,
 * broken constraint) and hide everything else.
 */
final class DataErrors {

	private DataErrors() {
	}

	static RuntimeException translate(DataAccessException e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		if (!(root instanceof SQLException sql) || sql.getSQLState() == null) {
			return e;
		}
		String state = sql.getSQLState();
		String message = sql.getMessage() == null ? "" : sql.getMessage().lines().findFirst().orElse("");
		if (state.equals("42501")) { // insufficient_privilege: table grant or row-level security said no
			return ApiException.forbidden("not allowed by this table's permission rules");
		}
		if (state.equals("57014")) { // query_canceled: our statement_timeout fired
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "timeout", "the query took too long");
		}
		if (state.startsWith("22")) { // data exception: value does not fit the column type
			return ApiException.badRequest("invalid_value", message);
		}
		if (state.startsWith("23")) { // integrity constraint: not null, unique, foreign key
			return ApiException.conflict("constraint_violation", message);
		}
		return e;
	}

}
