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

	public boolean isReference() {
		return references != null;
	}

}
