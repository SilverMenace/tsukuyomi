package dev.tsukuyomi.schema;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.SchemaOperation.AddColumn;
import dev.tsukuyomi.schema.SchemaOperation.ColumnSpec;
import dev.tsukuyomi.schema.SchemaOperation.CreateTable;
import dev.tsukuyomi.schema.SchemaOperation.DropColumn;
import dev.tsukuyomi.schema.SchemaOperation.DropTable;
import dev.tsukuyomi.schema.SchemaOperation.RenameColumn;
import dev.tsukuyomi.schema.SchemaOperation.RenameTable;
import dev.tsukuyomi.schema.SchemaOperation.SetNullable;
import dev.tsukuyomi.schema.SchemaOperation.SetUnique;
import dev.tsukuyomi.schema.SchemaOperation.SetRule;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantRole;

import static dev.tsukuyomi.schema.Identifiers.quote;

/**
 * Turns schema operations into SQL, and works out what the catalog looks like
 * afterwards.
 *
 * Pure logic: no database, no Spring. Given a catalog and a list of operations
 * it returns a plan or throws. That makes it easy to test and easy to reason
 * about: every name is validated here before it is embedded in SQL.
 */
public final class MigrationPlanner {

	/** The SQL to run and the catalog that will be true once it has run. */
	public record Plan(List<String> statements, Catalog result) {

		public String sql() {
			return String.join(";\n", statements) + ";";
		}

	}

	private static final List<ColumnDef> SYSTEM_COLUMNS = List.of(
			new ColumnDef("id", ColumnType.UUID, false, true, true),
			new ColumnDef("created_at", ColumnType.TIMESTAMPTZ, false, false, true));

	private final int maxTables;

	private final int maxColumns;

	private final int maxRows;

	public MigrationPlanner(int maxTables, int maxColumns, int maxRows) {
		this.maxTables = maxTables;
		this.maxColumns = maxColumns;
		this.maxRows = maxRows;
	}

	public Plan plan(ProjectRef project, Catalog current, List<SchemaOperation> operations) {
		if (operations == null || operations.isEmpty()) {
			throw ApiException.badRequest("no_operations", "a migration needs at least one operation");
		}
		List<String> statements = new ArrayList<>();
		Catalog catalog = current;
		for (SchemaOperation operation : operations) {
			if (operation == null) {
				throw ApiException.badRequest("invalid_operation", "operations must not contain null");
			}
			catalog = switch (operation) {
				case CreateTable op -> createTable(project, catalog, op, statements);
				case DropTable op -> dropTable(project, catalog, op, statements);
				case AddColumn op -> addColumn(project, catalog, op, statements);
				case DropColumn op -> dropColumn(project, catalog, op, statements);
				case RenameTable op -> renameTable(project, catalog, op, statements);
				case RenameColumn op -> renameColumn(project, catalog, op, statements);
				case SetNullable op -> setNullable(project, catalog, op, statements);
				case SetUnique op -> setUnique(project, catalog, op, statements);
				case SetRule op -> setRule(project, catalog, op, statements);
			};
		}
		return new Plan(statements, catalog.withVersion(current.version() + 1));
	}

	private Catalog createTable(ProjectRef project, Catalog catalog, CreateTable op, List<String> out) {
		Identifiers.requireValidTable(op.table());
		if (catalog.table(op.table()).isPresent()) {
			throw ApiException.conflict("table_exists", "table '" + op.table() + "' already exists");
		}
		if (catalog.tables().size() >= maxTables) {
			throw ApiException.conflict("quota_tables", "a project can have at most " + maxTables + " tables");
		}
		List<ColumnDef> columns = new ArrayList<>(SYSTEM_COLUMNS);
		for (ColumnSpec spec : op.columns() == null ? List.<ColumnSpec>of() : op.columns()) {
			columns.add(newColumn(catalog, op.table(), columns, spec));
		}

		String table = qualified(project, op.table());
		List<String> definitions = new ArrayList<>();
		// uuidv7 (PostgreSQL 18+) puts a timestamp in the first bits, so ids sort in
		// creation order. That keeps "order by created_at, id" equal to insertion
		// order even for rows inserted in one statement, and keeps the primary-key
		// index compact because new ids always land at its end.
		definitions.add("  \"id\" uuid primary key default uuidv7()");
		definitions.add("  \"created_at\" timestamptz not null default now()");
		columns.stream()
			.filter(c -> !c.system())
			.forEach(c -> definitions.add("  " + columnSql(project, op.table(), c)));
		out.add("create table " + table + " (\n" + String.join(",\n", definitions) + "\n)");
		columns.stream().filter(ColumnDef::isReference).forEach(c -> out.add(indexSql(project, op.table(), c)));

		// Row-level security on, and forced so that it applies even to the table's
		// owner. With no policies this means: nobody can see or change any row.
		out.add("alter table " + table + " enable row level security");
		out.add("alter table " + table + " force row level security");
		// Table privileges say "may touch this table at all"; the policies below
		// and in later migrations say "which rows".
		out.add("grant select, insert, update, delete on " + table + " to " + quote(project.roleName(TenantRole.ANON))
				+ ", " + quote(project.roleName(TenantRole.USER)) + ", "
				+ quote(project.roleName(TenantRole.SERVICE)));
		// The service key is for trusted server-side code: it may do everything.
		out.add("create policy \"service_all\" on " + table + " for all to "
				+ quote(project.roleName(TenantRole.SERVICE)) + " using (true) with check (true)");
		// Live updates: record every row change (see ChangeTrigger).
		out.add(ChangeTrigger.statement(table));
		// And refuse inserts beyond the row quota (see RowQuota).
		out.add(RowQuota.statement(table, maxRows));

		return catalog.withTable(new TableDef(op.table(), columns));
	}

	private Catalog dropTable(ProjectRef project, Catalog catalog, DropTable op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		requireNotBuiltin(table, "removed");
		for (TableDef other : catalog.tables()) {
			for (ColumnDef column : other.columns()) {
				if (!other.name().equals(table.name()) && table.name().equals(column.references())) {
					throw ApiException.conflict("table_referenced", "column '" + other.name() + "." + column.name()
							+ "' points at '" + table.name() + "'; remove that column first");
				}
			}
		}
		out.add("drop table " + qualified(project, table.name()));
		return catalog.withoutTable(table.name());
	}

	private Catalog addColumn(ProjectRef project, Catalog catalog, AddColumn op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		if (op.column() == null) {
			throw ApiException.badRequest("invalid_operation", "add_column needs a column");
		}
		ColumnDef column = newColumn(catalog, table.name(), table.columns(), op.column());
		if (table.builtin() && !column.nullable()) {
			// Signup creates the row with only an email, so extra profile columns must allow null.
			throw ApiException.badRequest("invalid_column", "columns added to '" + table.name() + "' must be nullable");
		}
		out.add("alter table " + qualified(project, table.name()) + " add column "
				+ columnSql(project, table.name(), column));
		if (column.isReference()) {
			out.add(indexSql(project, table.name(), column));
		}
		List<ColumnDef> columns = new ArrayList<>(table.columns());
		columns.add(column);
		return catalog.withTable(table.withColumns(columns));
	}

	private Catalog dropColumn(ProjectRef project, Catalog catalog, DropColumn op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		ColumnDef column = table.column(op.column() == null ? "" : op.column())
			.orElseThrow(() -> ApiException.badRequest("unknown_column",
					"table '" + table.name() + "' has no column '" + op.column() + "'"));
		if (column.system()) {
			throw ApiException.badRequest("system_column", "column '" + column.name() + "' cannot be removed");
		}
		table.rules().forEach((operation, rule) -> {
			if (column.name().equals(rule.ownerColumn())) {
				throw ApiException.conflict("column_in_rule", "the " + operation.sql() + " rule of '" + table.name()
						+ "' uses column '" + column.name() + "'; change the rule first");
			}
		});
		out.add("alter table " + qualified(project, table.name()) + " drop column " + quote(column.name()));
		List<ColumnDef> columns = new ArrayList<>(table.columns());
		columns.remove(column);
		return catalog.withTable(table.withColumns(columns));
	}

	/**
	 * Constraints and indexes carry the table's name inside their own names
	 * ("notes_slug_key"), and later migrations find them by that derived name.
	 * So a rename also renames them; otherwise "drop the unique constraint"
	 * would look for a name that no longer exists.
	 *
	 * Things that follow the table by themselves: its rows, its policies, its
	 * trigger, and foreign keys in other tables (Postgres tracks tables by an
	 * internal id, not by name). Only our catalog stores the name as text, so
	 * columns elsewhere that reference this table are updated there.
	 */
	private Catalog renameTable(ProjectRef project, Catalog catalog, RenameTable op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		requireNotBuiltin(table, "renamed");
		Identifiers.requireValidTable(op.to());
		if (catalog.table(op.to()).isPresent()) {
			throw ApiException.conflict("table_exists", "a table named '" + op.to() + "' already exists");
		}
		out.add("alter table " + qualified(project, table.name()) + " rename to " + quote(op.to()));
		for (ColumnDef column : table.columns()) {
			renameDerived(project, op.to(), table.name(), column.name(), op.to(), column.name(), column, out);
		}
		Catalog next = catalog.withoutTable(table.name());
		for (TableDef other : next.tables()) {
			for (ColumnDef column : other.columns()) {
				if (table.name().equals(column.references())) {
					next = next.withTable(next.table(other.name()).orElseThrow()
						.withColumn(column.name(), column.withReferences(op.to())));
				}
			}
		}
		// A table that references itself.
		TableDef renamed = table.withName(op.to());
		for (ColumnDef column : table.columns()) {
			if (table.name().equals(column.references())) {
				renamed = renamed.withColumn(column.name(), column.withReferences(op.to()));
			}
		}
		return next.withTable(renamed);
	}

	/**
	 * Policies that mention the column ("owner_id = tsk.uid()") keep working:
	 * Postgres stores the column inside a policy by position, not by name. Our
	 * catalog stores the rule's owner column as text, so it is updated here.
	 */
	private Catalog renameColumn(ProjectRef project, Catalog catalog, RenameColumn op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		ColumnDef column = changeableColumn(table, op.column());
		Identifiers.requireValidColumn(op.to());
		if (table.column(op.to()).isPresent()) {
			throw ApiException.conflict("column_exists",
					"table '" + table.name() + "' already has a column '" + op.to() + "'");
		}
		out.add("alter table " + qualified(project, table.name()) + " rename column " + quote(column.name()) + " to "
				+ quote(op.to()));
		renameDerived(project, table.name(), table.name(), column.name(), table.name(), op.to(), column, out);
		Map<RuleOperation, Rule> rules = new EnumMap<>(RuleOperation.class);
		table.rules()
			.forEach((operation, rule) -> rules.put(operation,
					column.name().equals(rule.ownerColumn()) ? new Rule(rule.kind(), op.to()) : rule));
		return catalog.withTable(table.withColumn(column.name(), column.withName(op.to())).withRules(rules));
	}

	/** Renames the unique constraint, foreign key and index that were named after (oldTable, oldColumn). */
	private static void renameDerived(ProjectRef project, String currentTable, String oldTable, String oldColumn,
			String newTable, String newColumn, ColumnDef column, List<String> out) {
		String target = qualified(project, currentTable);
		String before = oldTable + "_" + oldColumn;
		String after = newTable + "_" + newColumn;
		if (column.unique() && !column.system()) {
			out.add("alter table " + target + " rename constraint " + quote(before + "_key") + " to "
					+ quote(after + "_key"));
		}
		if (column.isReference()) {
			out.add("alter table " + target + " rename constraint " + quote(before + "_fkey") + " to "
					+ quote(after + "_fkey"));
			out.add("alter index \"" + project.schemaName() + "\"." + quote(before + "_idx") + " rename to "
					+ quote(after + "_idx"));
		}
	}

	/**
	 * "Required" (not null) or "optional". Making a column required fails in
	 * the database if some row has no value; the whole migration then rolls
	 * back and the owner sees the database's message.
	 */
	private Catalog setNullable(ProjectRef project, Catalog catalog, SetNullable op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		ColumnDef column = changeableColumn(table, op.column());
		if (op.nullable() == null) {
			throw ApiException.badRequest("invalid_operation", "set_nullable needs nullable: true or false");
		}
		if (column.nullable() == op.nullable()) {
			throw ApiException.badRequest("no_change", "column '" + column.name() + "' is already "
					+ (column.nullable() ? "optional" : "required"));
		}
		if (!op.nullable()) {
			if (table.builtin()) {
				// Signup creates the row with only an email.
				throw ApiException.badRequest("invalid_column",
						"columns added to '" + table.name() + "' must stay optional");
			}
			if (column.onDelete() == OnDelete.SET_NULL) {
				throw ApiException.badRequest("invalid_column",
						"this column is set to null when the row it references is deleted, so it must stay optional");
			}
		}
		out.add("alter table " + qualified(project, table.name()) + " alter column " + quote(column.name())
				+ (op.nullable() ? " drop not null" : " set not null"));
		return catalog.withTable(table.withColumn(column.name(), column.withNullable(op.nullable())));
	}

	/** Adding fails in the database if two rows already hold the same value. */
	private Catalog setUnique(ProjectRef project, Catalog catalog, SetUnique op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		ColumnDef column = changeableColumn(table, op.column());
		if (op.unique() == null) {
			throw ApiException.badRequest("invalid_operation", "set_unique needs unique: true or false");
		}
		if (column.unique() == op.unique()) {
			throw ApiException.badRequest("no_change",
					"column '" + column.name() + "' is already " + (column.unique() ? "unique" : "not unique"));
		}
		if (op.unique() && column.type() == ColumnType.JSONB) {
			throw ApiException.badRequest("invalid_column", "a jsonb column cannot be unique");
		}
		String constraint = quote(table.name() + "_" + column.name() + "_key");
		String target = qualified(project, table.name());
		out.add(op.unique()
				? "alter table " + target + " add constraint " + constraint + " unique (" + quote(column.name()) + ")"
				: "alter table " + target + " drop constraint " + constraint);
		return catalog.withTable(table.withColumn(column.name(), column.withUnique(op.unique())));
	}

	/** A column the owner created (not id, created_at or the built-in email). */
	private static ColumnDef changeableColumn(TableDef table, String name) {
		ColumnDef column = table.column(name == null ? "" : name)
			.orElseThrow(() -> ApiException.badRequest("unknown_column",
					"table '" + table.name() + "' has no column '" + name + "'"));
		if (column.system()) {
			throw ApiException.badRequest("system_column", "column '" + column.name() + "' cannot be changed");
		}
		return column;
	}

	/**
	 * A rule becomes a Postgres row-level security policy. The policy is what
	 * actually enforces it: the database checks it on every row, for every
	 * query, whatever the API code does.
	 *
	 *   anyone         policy for the anon and user roles, condition "true"
	 *   authenticated  policy for the user role only, condition "true"
	 *   owner          policy for the user role, condition "<column> = tsk.uid()"
	 *   nobody         no policy at all (row-level security denies by default)
	 *
	 * USING filters which existing rows are visible to the operation; WITH CHECK
	 * validates the new version of a row being written.
	 */
	private Catalog setRule(ProjectRef project, Catalog catalog, SetRule op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		if (op.operation() == null || op.rule() == null) {
			throw ApiException.badRequest("invalid_rule", "set_rule needs an operation and a rule");
		}
		if (table.builtin() && (op.operation() == RuleOperation.INSERT || op.operation() == RuleOperation.DELETE)
				&& op.rule() != RuleKind.NOBODY) {
			throw ApiException.badRequest("invalid_rule",
					"rows of '" + table.name() + "' are created by signup only; insert and delete stay closed");
		}
		String condition = "true";
		String owner = null;
		if (op.rule() == RuleKind.OWNER) {
			ColumnDef column = table.column(op.ownerColumn() == null ? "" : op.ownerColumn())
				.orElseThrow(() -> ApiException.badRequest("invalid_rule",
						"an owner rule needs ownerColumn, a uuid column of '" + table.name() + "'"));
			if (column.type() != ColumnType.UUID) {
				throw ApiException.badRequest("invalid_rule", "the owner column must be of type uuid");
			}
			owner = column.name();
			condition = quote(owner) + " = tsk.uid()";
		}
		else if (op.ownerColumn() != null) {
			throw ApiException.badRequest("invalid_rule", "ownerColumn only applies to the owner rule");
		}

		String target = qualified(project, table.name());
		String policy = "\"rule_" + op.operation().sql() + "\"";
		out.add("drop policy if exists " + policy + " on " + target);
		if (op.rule() != RuleKind.NOBODY) {
			String roles = quote(project.roleName(TenantRole.USER));
			if (op.rule() == RuleKind.ANYONE) {
				roles = quote(project.roleName(TenantRole.ANON)) + ", " + roles;
			}
			String clause = switch (op.operation()) {
				case SELECT, DELETE -> " using (" + condition + ")";
				case INSERT -> " with check (" + condition + ")";
				case UPDATE -> " using (" + condition + ") with check (" + condition + ")";
			};
			out.add("create policy " + policy + " on " + target + " for " + op.operation().sql() + " to " + roles
					+ clause);
			if (owner != null && !Identifiers.SYSTEM_COLUMNS.contains(owner)) {
				// Convenience: a client inserting a row need not send its own id;
				// the column fills itself with the caller's id.
				out.add("alter table " + target + " alter column " + quote(owner) + " set default tsk.uid()");
			}
		}
		return catalog.withTable(table.withRule(op.operation(), new Rule(op.rule(), owner)));
	}

	private static void requireNotBuiltin(TableDef table, String action) {
		if (table.builtin()) {
			throw ApiException.badRequest("builtin_table", "table '" + table.name() + "' is built in and cannot be " + action);
		}
	}

	private ColumnDef newColumn(Catalog catalog, String table, List<ColumnDef> existing, ColumnSpec spec) {
		if (spec == null || spec.type() == null) {
			throw ApiException.badRequest("invalid_column", "every column needs a name and a type");
		}
		Identifiers.requireValidColumn(spec.name());
		if (existing.stream().anyMatch(c -> c.name().equals(spec.name()))) {
			throw ApiException.conflict("column_exists",
					"table '" + table + "' already has a column '" + spec.name() + "'");
		}
		if (existing.size() >= maxColumns) {
			throw ApiException.conflict("quota_columns", "a table can have at most " + maxColumns + " columns");
		}
		if (spec.type() == ColumnType.JSONB && Boolean.TRUE.equals(spec.unique())) {
			throw ApiException.badRequest("invalid_column", "a jsonb column cannot be unique");
		}
		if (spec.references() != null) {
			// A reference always points at the target's id, which is a uuid.
			if (spec.type() != ColumnType.UUID) {
				throw ApiException.badRequest("invalid_column", "a column that references a table must be of type uuid");
			}
			boolean self = spec.references().equals(table);
			if (!self && catalog.table(spec.references()).isEmpty()) {
				throw ApiException.badRequest("unknown_table", "no table named '" + spec.references() + "' to reference");
			}
			if (self && catalog.table(table).isEmpty()) {
				throw ApiException.badRequest("invalid_column",
						"a table cannot reference itself while it is being created; add the column afterwards");
			}
			if (spec.onDelete() == OnDelete.SET_NULL && Boolean.FALSE.equals(spec.nullable())) {
				throw ApiException.badRequest("invalid_column", "set_null needs the column to be nullable");
			}
		}
		else if (spec.onDelete() != null) {
			throw ApiException.badRequest("invalid_column", "onDelete only applies to a column with references");
		}
		return spec.toDef();
	}

	private static TableDef existingTable(Catalog catalog, String name) {
		return catalog.table(name == null ? "" : name)
			.orElseThrow(() -> ApiException.badRequest("unknown_table", "no table named '" + name + "'"));
	}

	private static String columnSql(ProjectRef project, String table, ColumnDef column) {
		StringBuilder sql = new StringBuilder(quote(column.name())).append(' ').append(column.type().sql());
		if (!column.nullable()) {
			sql.append(" not null");
		}
		if (column.unique()) {
			// Named explicitly so a later migration can drop it by name.
			sql.append(" constraint ").append(quote(table + "_" + column.name() + "_key")).append(" unique");
		}
		if (column.isReference()) {
			sql.append(" constraint ")
				.append(quote(table + "_" + column.name() + "_fkey"))
				.append(" references ")
				.append(qualified(project, column.references()))
				.append(" (\"id\") on delete ")
				.append(column.onDelete().sql());
		}
		return sql.toString();
	}

	/**
	 * Postgres indexes the referenced side (the primary key) but not the
	 * referencing column. Expanding "all comments of this post" filters on that
	 * column, so without an index every expansion would scan the whole table.
	 */
	private static String indexSql(ProjectRef project, String table, ColumnDef column) {
		return "create index " + quote(table + "_" + column.name() + "_idx") + " on " + qualified(project, table)
				+ " (" + quote(column.name()) + ")";
	}

	private static String qualified(ProjectRef project, String table) {
		return "\"" + project.schemaName() + "\"." + quote(table);
	}

}
