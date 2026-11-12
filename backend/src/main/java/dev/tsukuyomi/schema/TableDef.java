package dev.tsukuyomi.schema;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One table as recorded in the catalog.
 *
 * @param columns in display order
 * @param rules the permission rule per operation; an operation without an
 * entry is closed to everyone but the service key
 * @param builtin true for tables the platform creates itself ("users")
 */
public record TableDef(String name, List<ColumnDef> columns, Map<RuleOperation, Rule> rules, boolean builtin) {

	public TableDef {
		columns = List.copyOf(columns);
		rules = Map.copyOf(rules);
	}

	public TableDef(String name, List<ColumnDef> columns) {
		this(name, columns, Map.of(), false);
	}

	public Optional<ColumnDef> column(String name) {
		return columns.stream().filter(c -> c.name().equals(name)).findFirst();
	}

	public Rule rule(RuleOperation operation) {
		return rules.getOrDefault(operation, Rule.NOBODY);
	}

	TableDef withColumns(List<ColumnDef> columns) {
		return new TableDef(name, columns, rules, builtin);
	}

	TableDef withRule(RuleOperation operation, Rule rule) {
		Map<RuleOperation, Rule> next = new EnumMap<>(RuleOperation.class);
		next.putAll(rules);
		if (rule.kind() == RuleKind.NOBODY) {
			next.remove(operation);
		}
		else {
			next.put(operation, rule);
		}
		return new TableDef(name, columns, next, builtin);
	}

}
