package dev.tsukuyomi.schema;

/**
 * One column as recorded in the catalog.
 *
 * @param references name of the table this column points at (a foreign key to
 * its id), or null for an ordinary column
 * @param onDelete what happens when the referenced row is deleted; null exactly
 * when references is null
 */
public record ColumnDef(String name, ColumnType type, boolean nullable, boolean unique, boolean system,
		String references, OnDelete onDelete) {

	public ColumnDef(String name, ColumnType type, boolean nullable, boolean unique, boolean system) {
		this(name, type, nullable, unique, system, null, null);
	}

	ColumnDef withName(String name) {
		return new ColumnDef(name, type, nullable, unique, system, references, onDelete);
	}

	ColumnDef withNullable(boolean nullable) {
		return new ColumnDef(name, type, nullable, unique, system, references, onDelete);
	}

	ColumnDef withUnique(boolean unique) {
		return new ColumnDef(name, type, nullable, unique, system, references, onDelete);
	}

	ColumnDef withReferences(String references) {
		return new ColumnDef(name, type, nullable, unique, system, references, onDelete);
	}

	public boolean isReference() {
		return references != null;
	}

}
