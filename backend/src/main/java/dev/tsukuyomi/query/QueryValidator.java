package dev.tsukuyomi.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.query.ast.Filter;
import dev.tsukuyomi.query.ast.Operator;
import dev.tsukuyomi.query.ast.OrderTerm;
import dev.tsukuyomi.query.ast.Param;
import dev.tsukuyomi.query.ast.Relation;
import dev.tsukuyomi.query.ast.SelectItem;
import dev.tsukuyomi.query.ast.SelectQuery;
import dev.tsukuyomi.query.ast.WriteCommand;
import dev.tsukuyomi.query.parse.RawQuery;
import dev.tsukuyomi.query.parse.RawQuery.RawFilter;
import dev.tsukuyomi.query.parse.RawQuery.RawOrder;
import dev.tsukuyomi.query.parse.RawQuery.RawSelectItem;
import dev.tsukuyomi.schema.Catalog;
import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.ColumnType;
import dev.tsukuyomi.schema.TableDef;

/**
 * Stage 2 of the query compiler: untrusted structure to a trusted AST.
 *
 * This is the trust boundary. Every table and column name in the request is
 * LOOKED UP in the catalog, and the AST stores the catalog's object
 * (TableDef / ColumnDef), never the request's string. If a name is not in the
 * catalog the request is rejected here. Request text survives only as filter
 * and body values, wrapped in Param.
 *
 * It also checks that each operator makes sense for the column's type and
 * that the request stays within QueryLimits.
 */
public final class QueryValidator {

	private static final Set<ColumnType> ORDERED_TYPES = Set.of(ColumnType.TEXT, ColumnType.INTEGER,
			ColumnType.BIGINT, ColumnType.NUMERIC, ColumnType.TIMESTAMPTZ, ColumnType.DATE);

	private final Catalog catalog;

	private final QueryLimits limits;

	/** Turns a JSON object or array into text for a jsonb column. */
	private final Function<Object, String> toJson;

	public QueryValidator(Catalog catalog, QueryLimits limits, Function<Object, String> toJson) {
		this.catalog = catalog;
		this.limits = limits;
		this.toJson = toJson;
	}

	// ---- reads ----------------------------------------------------------

	public SelectQuery select(String tableName, RawQuery raw) {
		TableDef table = table(tableName);
		int limit = raw.limit() == null ? limits.defaultLimit() : raw.limit();
		int offset = raw.offset() == null ? 0 : raw.offset();
		if (limit < 1 || limit > limits.maxLimit()) {
			throw invalid("limit must be between 1 and " + limits.maxLimit());
		}
		if (offset < 0 || offset > limits.maxOffset()) {
			throw invalid("offset must be between 0 and " + limits.maxOffset());
		}
		List<SelectItem> items = items(table, raw.select(), 0);
		long worstCase = worstCaseRows(items, limit);
		if (worstCase > limits.maxTotalRows()) {
			throw invalid("this query could return up to " + worstCase + " rows (max " + limits.maxTotalRows()
					+ "); lower the limit or remove a nested list");
		}
		return new SelectQuery(table, items, filters(table, raw.filters()), order(table, raw.order()), limit, offset);
	}

	/**
	 * The most rows this query could make Postgres produce, counting nested
	 * rows. Each to-many expansion can multiply its parent's row count by
	 * maxEmbedRows, so caps per level are not enough: 100 parents x 100 children
	 * x 100 grandchildren is a million rows from one URL. This adds the levels
	 * up so the request can be refused before it runs.
	 */
	private long worstCaseRows(List<SelectItem> items, long parentRows) {
		long total = parentRows;
		for (SelectItem item : items) {
			if (item instanceof SelectItem.Embed embed) {
				long rows = embed.relation().kind() == Relation.Kind.TO_MANY ? parentRows * limits.maxEmbedRows()
						: parentRows;
				total += worstCaseRows(embed.items(), rows);
			}
		}
		return total;
	}

	private List<SelectItem> items(TableDef table, List<RawSelectItem> raw, int depth) {
		List<SelectItem> items = new ArrayList<>();
		Set<String> keys = new HashSet<>(); // names of the JSON fields this level produces
		List<RawSelectItem> requested = raw.isEmpty() ? List.of(new RawSelectItem("*", null, null)) : raw;
		for (RawSelectItem item : requested) {
			if (item.isEmbed()) {
				if (depth >= limits.maxEmbedDepth()) {
					throw invalid("related records can be nested at most " + limits.maxEmbedDepth() + " levels deep");
				}
				Relation relation = relation(table, item.name(), item.hint());
				if (!keys.add(relation.target().name())) {
					throw invalid("'" + relation.target().name() + "' appears twice in select");
				}
				items.add(new SelectItem.Embed(relation, items(relation.target(), item.children(), depth + 1)));
			}
			else if (item.isStar()) {
				for (ColumnDef column : table.columns()) {
					if (keys.add(column.name())) {
						items.add(new SelectItem.Column(column));
					}
				}
			}
			else {
				ColumnDef column = column(table, item.name());
				if (keys.add(column.name())) {
					items.add(new SelectItem.Column(column));
				}
			}
		}
		return items;
	}

	/**
	 * Finds the one foreign key that links {@code parent} to the table named in
	 * the select. There are two directions, and both are tried:
	 * the parent points at the target (one object), or the target points at the
	 * parent (a list).
	 */
	private Relation relation(TableDef parent, String targetName, String hint) {
		TableDef target = table(targetName);
		if (target.name().equals(parent.name())) {
			throw invalid("expanding a table into itself is not supported");
		}
		List<Relation> found = new ArrayList<>();
		for (ColumnDef column : parent.columns()) {
			if (target.name().equals(column.references()) && (hint == null || hint.equals(column.name()))) {
				found.add(new Relation(Relation.Kind.TO_ONE, target, column));
			}
		}
		for (ColumnDef column : target.columns()) {
			if (parent.name().equals(column.references()) && (hint == null || hint.equals(column.name()))) {
				found.add(new Relation(Relation.Kind.TO_MANY, target, column));
			}
		}
		if (found.isEmpty()) {
			throw invalid("'" + parent.name() + "' has no relation to '" + target.name() + "'"
					+ (hint == null ? "" : " through that column"));
		}
		if (found.size() > 1) {
			throw invalid("more than one column links '" + parent.name() + "' and '" + target.name()
					+ "'; pick one with " + target.name() + "!column(...)");
		}
		return found.get(0);
	}

	private List<OrderTerm> order(TableDef table, List<RawOrder> raw) {
		List<OrderTerm> terms = new ArrayList<>();
		for (RawOrder term : raw) {
			ColumnDef column = column(table, term.column());
			if (column.type() == ColumnType.JSONB) {
				throw invalid("cannot order by jsonb column '" + column.name() + "'");
			}
			String direction = term.direction();
			String nulls = term.nulls();
			if (nulls == null && direction != null && direction.startsWith("nulls")) {
				nulls = direction; // "column.nullslast" without a direction
				direction = null;
			}
			boolean descending = switch (direction == null ? "asc" : direction) {
				case "asc" -> false;
				case "desc" -> true;
				default -> throw invalid("order direction must be asc or desc");
			};
			OrderTerm.Nulls nullsOrder = switch (nulls == null ? "" : nulls) {
				case "" -> OrderTerm.Nulls.DEFAULT;
				case "nullsfirst" -> OrderTerm.Nulls.FIRST;
				case "nullslast" -> OrderTerm.Nulls.LAST;
				default -> throw invalid("nulls order must be nullsfirst or nullslast");
			};
			terms.add(new OrderTerm(column, descending, nullsOrder));
		}
		return terms;
	}

	// ---- filters --------------------------------------------------------

	private List<Filter> filters(TableDef table, List<RawFilter> raw) {
		return raw.stream().map(f -> filter(table, f)).toList();
	}

	private Filter filter(TableDef table, RawFilter raw) {
		ColumnDef column = column(table, raw.column());
		ColumnType type = column.type();
		String value = raw.value();
		return switch (raw.operator()) {
			case "eq" -> comparison(column, Operator.EQ, raw, type != ColumnType.JSONB);
			case "neq" -> comparison(column, Operator.NEQ, raw, type != ColumnType.JSONB);
			case "gt" -> comparison(column, Operator.GT, raw, ORDERED_TYPES.contains(type));
			case "gte" -> comparison(column, Operator.GTE, raw, ORDERED_TYPES.contains(type));
			case "lt" -> comparison(column, Operator.LT, raw, ORDERED_TYPES.contains(type));
			case "lte" -> comparison(column, Operator.LTE, raw, ORDERED_TYPES.contains(type));
			case "like", "ilike" -> {
				requireSupported(column, raw.operator(), type == ColumnType.TEXT);
				// The API uses * as the wildcard because % must be escaped in URLs.
				Param pattern = new Param(value.replace('*', '%'));
				yield new Filter(column, raw.operator().equals("like") ? Operator.LIKE : Operator.ILIKE, raw.negated(),
						List.of(pattern));
			}
			case "in" -> {
				requireSupported(column, "in", type != ColumnType.JSONB);
				if (value.length() < 2 || !value.startsWith("(") || !value.endsWith(")")) {
					throw invalid("'in' needs a list in brackets, e.g. in.(a,b,c)");
				}
				String[] parts = value.substring(1, value.length() - 1).split(",", -1);
				if (parts.length > limits.maxInValues()) {
					throw invalid("'in' list is too long (max " + limits.maxInValues() + ")");
				}
				List<Param> values = new ArrayList<>();
				for (String part : parts) {
					values.add(new Param(part));
				}
				yield new Filter(column, Operator.IN, raw.negated(), values);
			}
			case "is" -> switch (value) {
				case "null" -> new Filter(column, Operator.IS_NULL, raw.negated(), List.of());
				case "true", "false" -> {
					requireSupported(column, "is." + value, type == ColumnType.BOOLEAN);
					yield new Filter(column, value.equals("true") ? Operator.IS_TRUE : Operator.IS_FALSE,
							raw.negated(), List.of());
				}
				default -> throw invalid("'is' accepts null, true or false");
			};
			default -> throw invalid("unknown operator in filter on '" + column.name()
					+ "'; use eq, neq, gt, gte, lt, lte, like, ilike, in or is");
		};
	}

	private Filter comparison(ColumnDef column, Operator operator, RawFilter raw, boolean supported) {
		requireSupported(column, raw.operator(), supported);
		return new Filter(column, operator, raw.negated(), List.of(new Param(raw.value())));
	}

	private static void requireSupported(ColumnDef column, String operator, boolean supported) {
		if (!supported) {
			throw invalid("operator '" + operator + "' cannot be used on " + column.type().sql() + " column '"
					+ column.name() + "'");
		}
	}

	// ---- writes ---------------------------------------------------------

	/** @param body the parsed JSON body: one object (Map) or an array of objects (List) */
	public WriteCommand.Insert insert(String tableName, Object body) {
		TableDef table = table(tableName);
		boolean single = body instanceof Map;
		List<?> objects = single ? List.of(body) : body instanceof List<?> list ? list : null;
		if (objects == null || objects.isEmpty()) {
			throw invalidBody("body must be a JSON object or a non-empty array of objects");
		}
		if (objects.size() > limits.maxBatchRows()) {
			throw invalidBody("at most " + limits.maxBatchRows() + " rows per request");
		}

		// Every row of one INSERT must set the same columns; take them from the first.
		Set<String> names = null;
		List<ColumnDef> columns = new ArrayList<>();
		List<List<Param>> rows = new ArrayList<>();
		for (Object object : objects) {
			Map<String, Object> fields = object(object);
			if (names == null) {
				names = new LinkedHashSet<>(fields.keySet());
				names.forEach(name -> columns.add(writableColumn(table, name)));
			}
			else if (!names.equals(fields.keySet())) {
				throw invalidBody("all rows in one request must have the same fields");
			}
			List<Param> row = new ArrayList<>();
			for (ColumnDef column : columns) {
				row.add(param(column, fields.get(column.name())));
			}
			rows.add(row);
		}
		if (columns.isEmpty() && rows.size() > 1) {
			throw invalidBody("rows in a batch must set at least one field");
		}
		return new WriteCommand.Insert(table, columns, rows, single);
	}

	public WriteCommand.Update update(String tableName, RawQuery raw, Object body) {
		TableDef table = table(tableName);
		Map<String, Object> fields = object(body);
		if (fields.isEmpty()) {
			throw invalidBody("body must set at least one field");
		}
		List<WriteCommand.Assignment> assignments = new ArrayList<>();
		fields.forEach((name, value) -> {
			ColumnDef column = writableColumn(table, name);
			assignments.add(new WriteCommand.Assignment(column, param(column, value)));
		});
		return new WriteCommand.Update(table, assignments, requiredFilters(table, raw, "update"));
	}

	public WriteCommand.Delete delete(String tableName, RawQuery raw) {
		TableDef table = table(tableName);
		return new WriteCommand.Delete(table, requiredFilters(table, raw, "delete"));
	}

	/**
	 * Updates and deletes must say which rows they mean. A request with no
	 * filter is far more likely to be a mistake than a wish to change every row.
	 */
	private List<Filter> requiredFilters(TableDef table, RawQuery raw, String action) {
		if (!raw.select().isEmpty() || !raw.order().isEmpty() || raw.limit() != null || raw.offset() != null) {
			throw invalid(action + " accepts only filters");
		}
		if (raw.filters().isEmpty()) {
			throw invalid(action + " needs at least one filter, e.g. ?id=eq.<id>");
		}
		return filters(table, raw.filters());
	}

	private Param param(ColumnDef column, Object value) {
		if (value == null) {
			return new Param(null);
		}
		boolean structured = value instanceof Map || value instanceof List;
		if (column.type() == ColumnType.JSONB) {
			return new Param(toJson.apply(value));
		}
		if (structured) {
			throw invalidBody("field '" + column.name() + "' must be a plain value, not an object or array");
		}
		return new Param(String.valueOf(value));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		if (!(value instanceof Map)) {
			throw invalidBody("each row must be a JSON object");
		}
		return (Map<String, Object>) value;
	}

	// ---- catalog lookups: the only way a name gets into the AST ---------

	private TableDef table(String name) {
		return catalog.table(name == null ? "" : name)
			.orElseThrow(() -> ApiException.notFound("no table named '" + shorten(name) + "'"));
	}

	private static ColumnDef column(TableDef table, String name) {
		return table.column(name == null ? "" : name)
			.orElseThrow(() -> invalid("table '" + table.name() + "' has no column '" + shorten(name) + "'"));
	}

	private static ColumnDef writableColumn(TableDef table, String name) {
		ColumnDef column = table.column(name == null ? "" : name)
			.orElseThrow(() -> invalidBody("table '" + table.name() + "' has no column '" + shorten(name) + "'"));
		if (column.system()) {
			throw invalidBody("column '" + column.name() + "' is set automatically and cannot be written");
		}
		return column;
	}

	private static String shorten(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 40 ? text : text.substring(0, 40) + "…";
	}

	private static ApiException invalid(String message) {
		return ApiException.badRequest("invalid_query", message);
	}

	private static ApiException invalidBody(String message) {
		return ApiException.badRequest("invalid_body", message);
	}

}
