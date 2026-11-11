package dev.tsukuyomi.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything we know about one project's tables at one schema version.
 *
 * Immutable: a migration produces a new Catalog instead of changing this one,
 * so a request that is half-way through keeps a consistent view.
 */
public record Catalog(int version, List<TableDef> tables) {

	public Catalog {
		tables = List.copyOf(tables);
	}

	public static Catalog empty() {
		return new Catalog(0, List.of());
	}

	public Optional<TableDef> table(String name) {
		return tables.stream().filter(t -> t.name().equals(name)).findFirst();
	}

	Catalog withTable(TableDef table) {
		List<TableDef> next = new ArrayList<>(tables);
		next.removeIf(t -> t.name().equals(table.name()));
		next.add(table);
		return new Catalog(version, next);
	}

	Catalog withoutTable(String name) {
		List<TableDef> next = new ArrayList<>(tables);
		next.removeIf(t -> t.name().equals(name));
		return new Catalog(version, next);
	}

	Catalog withVersion(int version) {
		return new Catalog(version, tables);
	}

}
