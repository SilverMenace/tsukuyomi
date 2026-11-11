package dev.tsukuyomi.schema;

import java.util.List;
import java.util.Optional;

/** One table as recorded in the catalog, columns in display order. */
public record TableDef(String name, List<ColumnDef> columns) {

	public TableDef {
		columns = List.copyOf(columns);
	}

	public Optional<ColumnDef> column(String name) {
		return columns.stream().filter(c -> c.name().equals(name)).findFirst();
	}

}
