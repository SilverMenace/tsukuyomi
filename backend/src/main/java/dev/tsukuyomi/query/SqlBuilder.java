package dev.tsukuyomi.query;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import dev.tsukuyomi.query.ast.Filter;
import dev.tsukuyomi.query.ast.OrderTerm;
import dev.tsukuyomi.query.ast.Param;
import dev.tsukuyomi.query.ast.Relation;
import dev.tsukuyomi.query.ast.SelectItem;
import dev.tsukuyomi.query.ast.SelectQuery;
import dev.tsukuyomi.query.ast.WriteCommand;
import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.Identifiers;
import dev.tsukuyomi.schema.TableDef;
import dev.tsukuyomi.tenancy.ProjectRef;

/**
 * Stage 3 of the query compiler: trusted AST to SQL.
 *
 * Why this cannot produce an injection:
 *
 *   1. No public method takes a String. The inputs are a ProjectRef and AST
 *      objects, so request text cannot be handed to this class by accident.
 *   2. SQL keywords and punctuation are string literals written in this file.
 *   3. Identifiers come from catalog objects and pass through
 *      Identifiers.quote, which re-checks the strict name pattern. Type names
 *      come from the ColumnType enum.
 *   4. Request values exist only as Param, and the only thing this class ever
 *      does with a Param is write "?" and add its text to the bind list.
 *
 * SqlBuilderContractTest checks rules 1 and 4 by reflection so they cannot be
 * broken unnoticed.
 *
 * One instance compiles one statement.
 */
public final class SqlBuilder {

	private final ProjectRef project;

	private final QueryLimits limits;

	private final StringBuilder sql = new StringBuilder();

	private final List<Object> parameters = new ArrayList<>();

	private int aliasCounter;

	private SqlBuilder(ProjectRef project, QueryLimits limits) {
		this.project = project;
		this.limits = limits;
	}

	// ---- entry points ---------------------------------------------------

	/** Rows as one JSON array, built by Postgres. */
	public static CompiledSql select(ProjectRef project, QueryLimits limits, SelectQuery query) {
		SqlBuilder b = new SqlBuilder(project, limits);
		String alias = b.nextAlias();
		b.sql.append("select coalesce(json_agg(row_to_json(r)), '[]'::json)::text from (select ");
		b.items(alias, query.items());
		b.sql.append(" from ").append(b.qualified(query.table())).append(" as ").append(alias);
		b.where(alias, query.filters());
		b.orderBy(alias, query.order());
		b.sql.append(" limit ").append(b.bind(query.limit()));
		b.sql.append(" offset ").append(b.bind(query.offset()));
		b.sql.append(") r");
		return b.done();
	}

	/** How many rows match the filters, ignoring limit and offset. */
	public static CompiledSql count(ProjectRef project, QueryLimits limits, SelectQuery query) {
		SqlBuilder b = new SqlBuilder(project, limits);
		String alias = b.nextAlias();
		b.sql.append("select count(*) from ").append(b.qualified(query.table())).append(" as ").append(alias);
		b.where(alias, query.filters());
		return b.done();
	}

	public static CompiledSql insert(ProjectRef project, QueryLimits limits, WriteCommand.Insert command) {
		SqlBuilder b = new SqlBuilder(project, limits);
		b.sql.append("with changed as (insert into ").append(b.qualified(command.table()));
		if (command.columns().isEmpty()) {
			b.sql.append(" default values");
		}
		else {
			StringJoiner names = new StringJoiner(", ", " (", ")");
			command.columns().forEach(column -> names.add(Identifiers.quote(column.name())));
			b.sql.append(names).append(" values ");
			StringJoiner rows = new StringJoiner(", ");
			for (List<Param> row : command.rows()) {
				StringJoiner values = new StringJoiner(", ", "(", ")");
				for (int i = 0; i < row.size(); i++) {
					values.add(b.bindAs(command.columns().get(i), row.get(i)));
				}
				rows.add(values.toString());
			}
			b.sql.append(rows);
		}
		b.returning(null, command.table());
		b.sql.append(command.single() ? ") select row_to_json(changed)::text from changed"
				: ") select coalesce(json_agg(row_to_json(changed)), '[]'::json)::text from changed");
		return b.done();
	}

	public static CompiledSql update(ProjectRef project, QueryLimits limits, WriteCommand.Update command) {
		SqlBuilder b = new SqlBuilder(project, limits);
		String alias = b.nextAlias();
		b.sql.append("with changed as (update ").append(b.qualified(command.table())).append(" as ").append(alias);
		StringJoiner assignments = new StringJoiner(", ", " set ", "");
		for (WriteCommand.Assignment assignment : command.assignments()) {
			assignments.add(Identifiers.quote(assignment.column().name()) + " = "
					+ b.bindAs(assignment.column(), assignment.value()));
		}
		b.sql.append(assignments);
		b.where(alias, command.filters());
		b.returning(alias, command.table());
		b.sql.append(") select coalesce(json_agg(row_to_json(changed)), '[]'::json)::text from changed");
		return b.done();
	}

	public static CompiledSql delete(ProjectRef project, QueryLimits limits, WriteCommand.Delete command) {
		SqlBuilder b = new SqlBuilder(project, limits);
		String alias = b.nextAlias();
		b.sql.append("with changed as (delete from ").append(b.qualified(command.table())).append(" as ").append(alias);
		b.where(alias, command.filters());
		b.returning(alias, command.table());
		b.sql.append(") select coalesce(json_agg(row_to_json(changed)), '[]'::json)::text from changed");
		return b.done();
	}

	// ---- select list and expansion --------------------------------------

	private void items(String alias, List<SelectItem> items) {
		boolean first = true;
		for (SelectItem item : items) {
			if (!first) {
				sql.append(", ");
			}
			first = false;
			switch (item) {
				case SelectItem.Column column -> sql.append(reference(alias, column.column()));
				case SelectItem.Embed embed -> embed(alias, embed);
			}
		}
	}

	/**
	 * Related records become a sub-select that Postgres turns into JSON and
	 * returns as one extra column of the parent row:
	 *
	 *   to-one:  (select row_to_json(e) from (select ... where child.id = parent.fk) e)
	 *   to-many: (select json_agg(row_to_json(e)) from (select ... where child.fk = parent.id ...) e)
	 *
	 * The sub-select runs as the same Postgres role, so row-level security on
	 * the related table applies to it exactly as it would to a direct query.
	 */
	private void embed(String parentAlias, SelectItem.Embed embed) {
		Relation relation = embed.relation();
		TableDef target = relation.target();
		String alias = nextAlias();
		String row = "e" + alias.substring(1);
		boolean many = relation.kind() == Relation.Kind.TO_MANY;

		sql.append(many ? "(select coalesce(json_agg(row_to_json(" + row + ")), '[]'::json) from (select "
				: "(select row_to_json(" + row + ") from (select ");
		items(alias, embed.items());
		sql.append(" from ").append(qualified(target)).append(" as ").append(alias).append(" where ");
		if (many) {
			sql.append(reference(alias, relation.foreignKey())).append(" = ").append(parentAlias).append(".\"id\"");
			sql.append(" order by ").append(alias).append(".\"created_at\", ").append(alias).append(".\"id\"");
			sql.append(" limit ").append(bind(limits.maxEmbedRows()));
		}
		else {
			sql.append(alias).append(".\"id\" = ").append(reference(parentAlias, relation.foreignKey()));
		}
		sql.append(") ").append(row).append(") as ").append(Identifiers.quote(target.name()));
	}

	// ---- where, order ---------------------------------------------------

	private void where(String alias, List<Filter> filters) {
		String separator = " where ";
		for (Filter filter : filters) {
			sql.append(separator);
			separator = " and ";
			if (filter.negated()) {
				sql.append("not (");
			}
			condition(alias, filter);
			if (filter.negated()) {
				sql.append(")");
			}
		}
	}

	private void condition(String alias, Filter filter) {
		ColumnDef column = filter.column();
		String reference = reference(alias, column);
		switch (filter.operator()) {
			case EQ -> sql.append(reference).append(" = ").append(bindAs(column, filter.values().get(0)));
			case NEQ -> sql.append(reference).append(" <> ").append(bindAs(column, filter.values().get(0)));
			case GT -> sql.append(reference).append(" > ").append(bindAs(column, filter.values().get(0)));
			case GTE -> sql.append(reference).append(" >= ").append(bindAs(column, filter.values().get(0)));
			case LT -> sql.append(reference).append(" < ").append(bindAs(column, filter.values().get(0)));
			case LTE -> sql.append(reference).append(" <= ").append(bindAs(column, filter.values().get(0)));
			case LIKE -> sql.append(reference).append(" like ").append(bind(filter.values().get(0)));
			case ILIKE -> sql.append(reference).append(" ilike ").append(bind(filter.values().get(0)));
			case IN -> {
				StringJoiner list = new StringJoiner(", ", " in (", ")");
				filter.values().forEach(value -> list.add(bindAs(column, value)));
				sql.append(reference).append(list);
			}
			case IS_NULL -> sql.append(reference).append(" is null");
			case IS_TRUE -> sql.append(reference).append(" is true");
			case IS_FALSE -> sql.append(reference).append(" is false");
		}
	}

	private void orderBy(String alias, List<OrderTerm> order) {
		StringJoiner terms = new StringJoiner(", ", " order by ", "");
		boolean byId = false;
		for (OrderTerm term : order) {
			String sqlTerm = reference(alias, term.column()) + (term.descending() ? " desc" : "") + switch (term.nulls()) {
				case DEFAULT -> "";
				case FIRST -> " nulls first";
				case LAST -> " nulls last";
			};
			terms.add(sqlTerm);
			byId |= term.column().name().equals("id");
		}
		if (order.isEmpty()) {
			terms.add(alias + ".\"created_at\"");
		}
		if (!byId) {
			// A unique last term makes the order total, so pages never overlap or skip rows.
			terms.add(alias + ".\"id\"");
		}
		sql.append(terms);
	}

	private void returning(String alias, TableDef table) {
		StringJoiner columns = new StringJoiner(", ", " returning ", "");
		table.columns().forEach(column -> columns.add(alias == null ? Identifiers.quote(column.name())
				: reference(alias, column)));
		sql.append(columns);
	}

	// ---- the only four ways anything gets into the SQL text -------------

	/** "schema"."table": schema from ProjectRef (validated), table re-checked by quote. */
	private String qualified(TableDef table) {
		return "\"" + project.schemaName() + "\"." + Identifiers.quote(table.name());
	}

	/** alias."column": alias generated here, column re-checked by quote. */
	private static String reference(String alias, ColumnDef column) {
		return alias + "." + Identifiers.quote(column.name());
	}

	/**
	 * A request value, converted by Postgres to the column's type. The value is
	 * sent as text; if it does not fit the type, Postgres rejects it and the
	 * client gets a 400.
	 */
	private String bindAs(ColumnDef column, Param value) {
		parameters.add(value.text());
		return "cast(? as " + column.type().sql() + ")";
	}

	private String bind(Param value) {
		parameters.add(value.text());
		return "?";
	}

	private String bind(int value) {
		parameters.add(value);
		return "?";
	}

	private String nextAlias() {
		return "t" + aliasCounter++;
	}

	private CompiledSql done() {
		return new CompiledSql(sql.toString(), parameters);
	}

}
