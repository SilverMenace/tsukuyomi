package dev.tsukuyomi.schema;

/**
 * The trigger that feeds live updates. Every project table gets one; it calls
 * tsk.record_change() (see V4__change_events.sql) after each row change.
 */
public final class ChangeTrigger {

	private ChangeTrigger() {
	}

	/** @param qualifiedTable the quoted "schema"."table" name */
	public static String statement(String qualifiedTable) {
		return "create trigger \"tsk_change\" after insert or update or delete on " + qualifiedTable
				+ " for each row execute function tsk.record_change()";
	}

}
