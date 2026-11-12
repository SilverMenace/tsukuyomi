package dev.tsukuyomi.query.ast;

import java.util.List;

import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.TableDef;

/** A fully validated write. */
public sealed interface WriteCommand {

	TableDef table();

	/**
	 * @param columns the columns being set, the same for every row
	 * @param rows one list of values per row, in the order of columns
	 * @param single true when the request body was one object rather than an array
	 */
	record Insert(TableDef table, List<ColumnDef> columns, List<List<Param>> rows, boolean single)
			implements WriteCommand {
	}

	record Assignment(ColumnDef column, Param value) {
	}

	record Update(TableDef table, List<Assignment> assignments, List<Filter> filters) implements WriteCommand {
	}

	record Delete(TableDef table, List<Filter> filters) implements WriteCommand {
	}

}
