package dev.tsukuyomi.schema;

import java.util.ArrayList;
import java.util.List;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.SchemaOperation.AddColumn;
import dev.tsukuyomi.schema.SchemaOperation.ColumnSpec;
import dev.tsukuyomi.schema.SchemaOperation.CreateTable;
import dev.tsukuyomi.schema.SchemaOperation.DropColumn;
import dev.tsukuyomi.schema.SchemaOperation.DropTable;
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

	public MigrationPlanner(int maxTables, int maxColumns) {
		this.maxTables = maxTables;
		this.maxColumns = maxColumns;
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
			columns.add(newColumn(op.table(), columns, spec));
		}

		String table = qualified(project, op.table());
		List<String> definitions = new ArrayList<>();
		definitions.add("  \"id\" uuid primary key default gen_random_uuid()");
		definitions.add("  \"created_at\" timestamptz not null default now()");
		columns.stream().filter(c -> !c.system()).forEach(c -> definitions.add("  " + columnSql(op.table(), c)));
		out.add("create table " + table + " (\n" + String.join(",\n", definitions) + "\n)");

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

		return catalog.withTable(new TableDef(op.table(), columns));
	}

	private Catalog dropTable(ProjectRef project, Catalog catalog, DropTable op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		out.add("drop table " + qualified(project, table.name()));
		return catalog.withoutTable(table.name());
	}

	private Catalog addColumn(ProjectRef project, Catalog catalog, AddColumn op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		if (op.column() == null) {
			throw ApiException.badRequest("invalid_operation", "add_column needs a column");
		}
		ColumnDef column = newColumn(table.name(), table.columns(), op.column());
		out.add("alter table " + qualified(project, table.name()) + " add column " + columnSql(table.name(), column));
		List<ColumnDef> columns = new ArrayList<>(table.columns());
		columns.add(column);
		return catalog.withTable(new TableDef(table.name(), columns));
	}

	private Catalog dropColumn(ProjectRef project, Catalog catalog, DropColumn op, List<String> out) {
		TableDef table = existingTable(catalog, op.table());
		ColumnDef column = table.column(op.column() == null ? "" : op.column())
			.orElseThrow(() -> ApiException.badRequest("unknown_column",
					"table '" + table.name() + "' has no column '" + op.column() + "'"));
		if (column.system()) {
			throw ApiException.badRequest("system_column", "column '" + column.name() + "' cannot be removed");
		}
		out.add("alter table " + qualified(project, table.name()) + " drop column " + quote(column.name()));
		List<ColumnDef> columns = new ArrayList<>(table.columns());
		columns.remove(column);
		return catalog.withTable(new TableDef(table.name(), columns));
	}

	private ColumnDef newColumn(String table, List<ColumnDef> existing, ColumnSpec spec) {
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
		return spec.toDef();
	}

	private static TableDef existingTable(Catalog catalog, String name) {
		return catalog.table(name == null ? "" : name)
			.orElseThrow(() -> ApiException.badRequest("unknown_table", "no table named '" + name + "'"));
	}

	private static String columnSql(String table, ColumnDef column) {
		StringBuilder sql = new StringBuilder(quote(column.name())).append(' ').append(column.type().sql());
		if (!column.nullable()) {
			sql.append(" not null");
		}
		if (column.unique()) {
			// Named explicitly so a later migration can drop it by name.
			sql.append(" constraint ").append(quote(table + "_" + column.name() + "_key")).append(" unique");
		}
		return sql.toString();
	}

	private static String qualified(ProjectRef project, String table) {
		return "\"" + project.schemaName() + "\"." + quote(table);
	}

}
