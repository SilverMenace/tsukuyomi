package dev.tsukuyomi.query.parse;

import java.util.List;

/**
 * The query string split into its parts, nothing more. Every field here is
 * still untrusted text from the request: no name has been checked against the
 * catalog yet. Only QueryValidator turns this into something SQL may be built from.
 */
public record RawQuery(List<RawSelectItem> select, List<RawFilter> filters, List<RawOrder> order, Integer limit,
		Integer offset) {

	/**
	 * @param name a column name, a related table name, or "*"
	 * @param hint the column after "!" that picks between several relations, or null
	 * @param children the nested select list, or null when this is not an expansion
	 */
	public record RawSelectItem(String name, String hint, List<RawSelectItem> children) {

		public boolean isStar() {
			return "*".equals(name);
		}

		public boolean isEmbed() {
			return children != null;
		}

	}

	/** e.g. priority=not.gte.3 gives ("priority", true, "gte", "3"). */
	public record RawFilter(String column, boolean negated, String operator, String value) {
	}

	/** e.g. created_at.desc.nullslast gives ("created_at", "desc", "nullslast"). */
	public record RawOrder(String column, String direction, String nulls) {
	}

}
