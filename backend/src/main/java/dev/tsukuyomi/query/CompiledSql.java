package dev.tsukuyomi.query;

import java.util.List;

/**
 * The compiler's output: SQL text containing only "?" where values go, and
 * the values to bind, in order.
 */
public record CompiledSql(String sql, List<Object> parameters) {

	public CompiledSql {
		parameters = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(parameters));
	}

}
