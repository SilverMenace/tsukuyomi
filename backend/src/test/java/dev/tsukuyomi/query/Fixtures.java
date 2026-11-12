package dev.tsukuyomi.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.tsukuyomi.query.parse.QueryParser;
import dev.tsukuyomi.schema.Catalog;
import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.ColumnType;
import dev.tsukuyomi.schema.OnDelete;
import dev.tsukuyomi.schema.TableDef;
import dev.tsukuyomi.tenancy.ProjectRef;

/** A small blog schema used by the compiler's unit tests. No database involved. */
final class Fixtures {

	static final ProjectRef PROJECT = new ProjectRef(UUID.randomUUID(), "p_0123456789ab");

	static final QueryLimits LIMITS = QueryLimits.DEFAULT;

	static final Catalog CATALOG = new Catalog(1, List.of(
			table("authors", column("name", ColumnType.TEXT)),
			table("posts", column("title", ColumnType.TEXT), column("priority", ColumnType.INTEGER),
					column("published", ColumnType.BOOLEAN), column("meta", ColumnType.JSONB),
					reference("author_id", "authors"), reference("editor_id", "authors")),
			table("comments", column("body", ColumnType.TEXT), reference("post_id", "posts"))));

	private Fixtures() {
	}

	static TableDef table(String name, ColumnDef... columns) {
		List<ColumnDef> all = new java.util.ArrayList<>(List.of(new ColumnDef("id", ColumnType.UUID, false, true, true),
				new ColumnDef("created_at", ColumnType.TIMESTAMPTZ, false, false, true)));
		all.addAll(List.of(columns));
		return new TableDef(name, all);
	}

	static ColumnDef column(String name, ColumnType type) {
		return new ColumnDef(name, type, true, false, false);
	}

	static ColumnDef reference(String name, String table) {
		return new ColumnDef(name, ColumnType.UUID, true, false, false, table, OnDelete.RESTRICT);
	}

	static QueryValidator validator() {
		return new QueryValidator(CATALOG, LIMITS, String::valueOf);
	}

	/** Parse + validate + build a SELECT, exactly as RowService does. */
	static CompiledSql select(String table, Map<String, List<String>> parameters) {
		return SqlBuilder.select(PROJECT, LIMITS,
				validator().select(table, new QueryParser(LIMITS).parse(parameters)));
	}

	/** Convenience: name1, value1, name2, value2, ... */
	static Map<String, List<String>> params(String... pairs) {
		Map<String, List<String>> map = new java.util.LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.computeIfAbsent(pairs[i], k -> new java.util.ArrayList<>()).add(pairs[i + 1]);
		}
		return map;
	}

}
