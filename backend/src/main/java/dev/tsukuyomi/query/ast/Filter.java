package dev.tsukuyomi.query.ast;

import java.util.List;

import dev.tsukuyomi.schema.ColumnDef;

/**
 * One condition on a column, e.g. priority >= 3.
 *
 * @param values one value for comparisons, several for IN, none for IS_*
 */
public record Filter(ColumnDef column, Operator operator, boolean negated, List<Param> values) {

	public Filter {
		values = List.copyOf(values);
	}

}
