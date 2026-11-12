package dev.tsukuyomi.schema;

/**
 * A permission rule for one operation on one table.
 *
 * @param ownerColumn for OWNER rules, the uuid column holding the owning user's
 * id; null otherwise
 */
public record Rule(RuleKind kind, String ownerColumn) {

	public static final Rule NOBODY = new Rule(RuleKind.NOBODY, null);

}
