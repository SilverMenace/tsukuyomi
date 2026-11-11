package dev.tsukuyomi.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One change to a project's schema, as sent by the dashboard.
 *
 * The dashboard never sends SQL. It sends a list of these, for example
 * {"op": "add_column", "table": "notes", "column": {"name": "title", "type": "text"}}
 * and the server generates the SQL (see MigrationPlanner).
 *
 * "sealed" means the list of implementations below is complete and the
 * compiler knows it, so a switch over an operation must handle every kind.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({ @JsonSubTypes.Type(value = SchemaOperation.CreateTable.class, name = "create_table"),
		@JsonSubTypes.Type(value = SchemaOperation.DropTable.class, name = "drop_table"),
		@JsonSubTypes.Type(value = SchemaOperation.AddColumn.class, name = "add_column"),
		@JsonSubTypes.Type(value = SchemaOperation.DropColumn.class, name = "drop_column") })
public sealed interface SchemaOperation {

	/** A column as described in a request. Missing flags mean "nullable, not unique". */
	record ColumnSpec(String name, ColumnType type, Boolean nullable, Boolean unique) {

		ColumnDef toDef() {
			return new ColumnDef(name, type, nullable == null || nullable, unique != null && unique, false);
		}

	}

	record CreateTable(String table, List<ColumnSpec> columns) implements SchemaOperation {
	}

	record DropTable(String table) implements SchemaOperation {
	}

	record AddColumn(String table, ColumnSpec column) implements SchemaOperation {
	}

	record DropColumn(String table, String column) implements SchemaOperation {
	}

}
