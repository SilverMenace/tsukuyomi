package dev.tsukuyomi.query.ast;

import dev.tsukuyomi.schema.ColumnDef;

public record OrderTerm(ColumnDef column, boolean descending, Nulls nulls) {

	public enum Nulls {

		DEFAULT, FIRST, LAST

	}

}
