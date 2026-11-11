package dev.tsukuyomi.schema;

/** One column as recorded in the catalog. */
public record ColumnDef(String name, ColumnType type, boolean nullable, boolean unique, boolean system) {
}
