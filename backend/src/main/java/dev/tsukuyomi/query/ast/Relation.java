package dev.tsukuyomi.query.ast;

import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.TableDef;

/**
 * How a parent table is linked to the table being expanded.
 *
 * TO_ONE: the parent holds the foreign key (post.author_id -> users). One object.
 * TO_MANY: the target holds the foreign key (comments.post_id -> post). A list.
 *
 * @param target the table whose rows are nested
 * @param foreignKey the referencing column: on the parent for TO_ONE, on the
 * target for TO_MANY
 */
public record Relation(Kind kind, TableDef target, ColumnDef foreignKey) {

	public enum Kind {

		TO_ONE, TO_MANY

	}

}
