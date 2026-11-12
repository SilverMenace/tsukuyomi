package dev.tsukuyomi.query.ast;

import java.util.List;

import dev.tsukuyomi.schema.TableDef;

/** A fully validated read: every name has been resolved to a catalog object. */
public record SelectQuery(TableDef table, List<SelectItem> items, List<Filter> filters, List<OrderTerm> order,
		int limit, int offset) {

	public SelectQuery {
		items = List.copyOf(items);
		filters = List.copyOf(filters);
		order = List.copyOf(order);
	}

}
