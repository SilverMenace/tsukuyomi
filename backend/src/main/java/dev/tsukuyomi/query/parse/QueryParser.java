package dev.tsukuyomi.query.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.query.QueryLimits;
import dev.tsukuyomi.query.parse.RawQuery.RawFilter;
import dev.tsukuyomi.query.parse.RawQuery.RawOrder;
import dev.tsukuyomi.query.parse.RawQuery.RawSelectItem;

/**
 * Stage 1 of the query compiler: text to structure.
 *
 *   select=id,title,author(email),comments(id,body)
 *   status=eq.open   priority=not.gte.3   id=in.(a,b)   deleted_at=is.null
 *   order=created_at.desc,title   limit=20   offset=40
 *
 * The parser only understands the SHAPE of a query. It does not know which
 * tables or columns exist and trusts nothing; its output is still plain text
 * (see RawQuery). It enforces size limits so later stages never see huge input.
 */
public final class QueryParser {

	private static final Set<String> RESERVED = Set.of("select", "order", "limit", "offset");

	private final QueryLimits limits;

	public QueryParser(QueryLimits limits) {
		this.limits = limits;
	}

	/** @param parameters the decoded query string: name to its values, in order */
	public RawQuery parse(Map<String, List<String>> parameters) {
		List<RawSelectItem> select = List.of();
		List<RawOrder> order = List.of();
		Integer limit = null;
		Integer offset = null;
		List<RawFilter> filters = new ArrayList<>();

		for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
			String name = parameter.getKey();
			List<String> values = parameter.getValue();
			if (RESERVED.contains(name)) {
				if (values.size() != 1) {
					throw invalid("'" + name + "' may appear only once");
				}
				String value = values.get(0);
				switch (name) {
					case "select" -> select = parseSelect(value);
					case "order" -> order = parseOrder(value);
					case "limit" -> limit = parseNumber("limit", value);
					default -> offset = parseNumber("offset", value);
				}
			}
			else {
				for (String value : values) {
					filters.add(parseFilter(name, value));
				}
			}
		}
		if (filters.size() > limits.maxFilters()) {
			throw invalid("too many filters (max " + limits.maxFilters() + ")");
		}
		return new RawQuery(select, filters, order, limit, offset);
	}

	// ---- select ---------------------------------------------------------

	private List<RawSelectItem> parseSelect(String text) {
		if (text.length() > limits.maxSelectLength()) {
			throw invalid("select is too long");
		}
		Cursor cursor = new Cursor(text);
		List<RawSelectItem> items = parseItems(cursor, 0);
		if (!cursor.atEnd()) {
			throw invalid("unexpected '" + cursor.peek() + "' in select at position " + cursor.position);
		}
		return items;
	}

	/**
	 * items := item (',' item)*
	 * item  := '*' | name | name ['!' name] '(' items ')'
	 *
	 * A small recursive-descent parser: each grammar rule is one method, and a
	 * nested list is handled by the method calling itself.
	 */
	private List<RawSelectItem> parseItems(Cursor cursor, int depth) {
		List<RawSelectItem> items = new ArrayList<>();
		do {
			String name = cursor.readName();
			if (name.isEmpty()) {
				throw invalid("expected a column or table name in select at position " + cursor.position);
			}
			String hint = null;
			if (cursor.take('!')) {
				hint = cursor.readName();
				if (hint.isEmpty()) {
					throw invalid("expected a column name after '!' in select");
				}
			}
			List<RawSelectItem> children = null;
			if (cursor.take('(')) {
				if (depth >= limits.maxEmbedDepth()) {
					throw invalid("related records can be nested at most " + limits.maxEmbedDepth() + " levels deep");
				}
				children = parseItems(cursor, depth + 1);
				if (!cursor.take(')')) {
					throw invalid("missing ')' in select");
				}
			}
			else if (hint != null) {
				throw invalid("'!' is only used when expanding a related table, e.g. comments!post_id(id)");
			}
			items.add(new RawSelectItem(name, hint, children));
		}
		while (cursor.take(','));
		return items;
	}

	/** A position in the select text. */
	private static final class Cursor {

		private final String text;

		private int position;

		Cursor(String text) {
			this.text = text;
		}

		boolean atEnd() {
			return position >= text.length();
		}

		char peek() {
			return text.charAt(position);
		}

		boolean take(char expected) {
			if (!atEnd() && peek() == expected) {
				position++;
				return true;
			}
			return false;
		}

		/** Reads up to the next structural character. May return "" or "*". */
		String readName() {
			int start = position;
			while (!atEnd() && ",()!".indexOf(peek()) < 0) {
				position++;
			}
			return text.substring(start, position).trim();
		}

	}

	// ---- filters --------------------------------------------------------

	private RawFilter parseFilter(String column, String text) {
		if (text.length() > limits.maxValueLength()) {
			throw invalid("filter value for '" + shorten(column) + "' is too long");
		}
		String rest = text;
		boolean negated = false;
		if (rest.startsWith("not.")) {
			negated = true;
			rest = rest.substring(4);
		}
		int dot = rest.indexOf('.');
		if (dot <= 0) {
			throw invalid("filter on '" + shorten(column) + "' must look like <operator>.<value>, e.g. eq.5");
		}
		return new RawFilter(column, negated, rest.substring(0, dot), rest.substring(dot + 1));
	}

	// ---- order, limit, offset -------------------------------------------

	private List<RawOrder> parseOrder(String text) {
		List<RawOrder> terms = new ArrayList<>();
		for (String term : text.split(",", -1)) {
			String[] parts = term.trim().split("\\.", -1);
			if (parts.length > 3 || parts[0].isEmpty()) {
				throw invalid("order must look like column[.asc|.desc][.nullsfirst|.nullslast]");
			}
			terms.add(new RawOrder(parts[0], parts.length > 1 ? parts[1] : null, parts.length > 2 ? parts[2] : null));
		}
		if (terms.size() > limits.maxOrderTerms()) {
			throw invalid("too many order terms (max " + limits.maxOrderTerms() + ")");
		}
		return terms;
	}

	private static Integer parseNumber(String name, String text) {
		try {
			return Integer.valueOf(text);
		}
		catch (NumberFormatException e) {
			throw invalid("'" + name + "' must be a whole number");
		}
	}

	static String shorten(String text) {
		return text.length() <= 40 ? text : text.substring(0, 40) + "…";
	}

	private static ApiException invalid(String message) {
		return ApiException.badRequest("invalid_query", message);
	}

}
