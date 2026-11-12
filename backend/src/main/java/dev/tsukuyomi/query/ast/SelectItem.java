package dev.tsukuyomi.query.ast;

import java.util.List;

import dev.tsukuyomi.schema.ColumnDef;

/** One entry of the select list: a plain column, or related records nested under a key. */
public sealed interface SelectItem {

	record Column(ColumnDef column) implements SelectItem {
	}

	record Embed(Relation relation, List<SelectItem> items) implements SelectItem {

		public Embed {
			items = List.copyOf(items);
		}

	}

}
