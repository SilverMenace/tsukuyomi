package dev.tsukuyomi.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes the catalog tables (table_meta, column_meta) and the migration history. */
@Repository
class CatalogRepository {

	record MigrationRecord(int version, String operations, String sql, java.time.Instant appliedAt) {
	}

	private final JdbcTemplate jdbc;

	CatalogRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks the project row until the surrounding transaction ends, so two
	 * migrations for the same project cannot run at the same time.
	 */
	int lockVersion(UUID projectId) {
		return jdbc.queryForObject("select schema_version from tsk_platform.project where id = ? for update",
				Integer.class, projectId);
	}

	Catalog load(UUID projectId) {
		Integer version = jdbc.queryForObject("select schema_version from tsk_platform.project where id = ?",
				Integer.class, projectId);
		Map<String, List<ColumnDef>> tables = new LinkedHashMap<>();
		jdbc.query("select name from tsk_platform.table_meta where project_id = ? order by name",
				rs -> { tables.put(rs.getString("name"), new ArrayList<>()); }, projectId);
		jdbc.query("""
				select t.name as table_name, c.name, c.type, c.nullable, c.is_unique, c.is_system, c.ref_table, c.on_delete
				from tsk_platform.column_meta c join tsk_platform.table_meta t on t.id = c.table_id
				where t.project_id = ? order by t.name, c.position""", rs -> {
			tables.get(rs.getString("table_name"))
				.add(new ColumnDef(rs.getString("name"), ColumnType.parse(rs.getString("type")),
						rs.getBoolean("nullable"), rs.getBoolean("is_unique"), rs.getBoolean("is_system"),
						rs.getString("ref_table"),
						rs.getString("on_delete") == null ? null : OnDelete.parse(rs.getString("on_delete"))));
		}, projectId);
		List<TableDef> defs = tables.entrySet().stream().map(e -> new TableDef(e.getKey(), e.getValue())).toList();
		return new Catalog(version, defs);
	}

	/**
	 * Stores the catalog as it is after a migration. The catalog is small (a
	 * few tables), so we replace it whole rather than patching rows: there is
	 * then no way for the stored catalog to drift from what the planner computed.
	 */
	void replace(UUID projectId, Catalog catalog) {
		jdbc.update("delete from tsk_platform.table_meta where project_id = ?", projectId);
		for (TableDef table : catalog.tables()) {
			UUID tableId = jdbc.queryForObject(
					"insert into tsk_platform.table_meta (project_id, name) values (?, ?) returning id", UUID.class,
					projectId, table.name());
			int position = 0;
			for (ColumnDef column : table.columns()) {
				jdbc.update("""
						insert into tsk_platform.column_meta
						  (table_id, name, type, nullable, is_unique, is_system, position, ref_table, on_delete)
						values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", tableId, column.name(), column.type().sql(), column.nullable(),
						column.unique(), column.system(), position++, column.references(),
						column.onDelete() == null ? null : column.onDelete().json());
			}
		}
		jdbc.update("update tsk_platform.project set schema_version = ? where id = ?", catalog.version(), projectId);
	}

	void insertMigration(UUID projectId, int version, String operationsJson, String sql) {
		jdbc.update("""
				insert into tsk_platform.migration (project_id, version, operations, sql)
				values (?, ?, cast(? as jsonb), ?)""", projectId, version, operationsJson, sql);
	}

	List<MigrationRecord> migrations(UUID projectId) {
		return jdbc.query("""
				select version, operations::text as operations, sql, applied_at
				from tsk_platform.migration where project_id = ? order by version desc""",
				(rs, n) -> new MigrationRecord(rs.getInt("version"), rs.getString("operations"), rs.getString("sql"),
						rs.getTimestamp("applied_at").toInstant()),
				projectId);
	}

}
