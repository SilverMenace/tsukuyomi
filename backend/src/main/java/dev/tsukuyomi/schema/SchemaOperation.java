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
		@JsonSubTypes.Type(value = SchemaOperation.DropColumn.class, name = "drop_column"),
		@JsonSubTypes.Type(value = SchemaOperation.RenameTable.class, name = "rename_table"),
		@JsonSubTypes.Type(value = SchemaOperation.RenameColumn.class, name = "rename_column"),
		@JsonSubTypes.Type(value = SchemaOperation.SetNullable.class, name = "set_nullable"),
		@JsonSubTypes.Type(value = SchemaOperation.SetUnique.class, name = "set_unique"),
		@JsonSubTypes.Type(value = SchemaOperation.SetRule.class, name = "set_rule") })
public sealed interface SchemaOperation {

	/**
	 * A column as described in a request. Missing flags mean "nullable, not
	 * unique". "references" names another table of the project and turns the
	 * column into a foreign key to that table's id.
	 */
	record ColumnSpec(String name, ColumnType type, Boolean nullable, Boolean unique, String references,
			OnDelete onDelete) {

		public ColumnSpec(String name, ColumnType type, Boolean nullable, Boolean unique) {
			this(name, type, nullable, unique, null, null);
		}

		ColumnDef toDef() {
			boolean isNullable = nullable == null || nullable;
			OnDelete action = references == null ? null : (onDelete == null ? OnDelete.RESTRICT : onDelete);
			return new ColumnDef(name, type, isNullable, unique != null && unique, false, references, action);
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

	record RenameTable(String table, String to) implements SchemaOperation {
	}

	record RenameColumn(String table, String column, String to) implements SchemaOperation {
	}

	/** nullable=false means every row must have a value ("required"). */
	record SetNullable(String table, String column, Boolean nullable) implements SchemaOperation {
	}

	/** unique=true means no two rows may hold the same value. */
	record SetUnique(String table, String column, Boolean unique) implements SchemaOperation {
	}

	/**
	 * Sets who may perform one operation on a table.
	 *
	 * @param ownerColumn required for (and only for) the "owner" rule
	 */
	record SetRule(String table, RuleOperation operation, RuleKind rule, String ownerColumn) implements SchemaOperation {
	}

}
