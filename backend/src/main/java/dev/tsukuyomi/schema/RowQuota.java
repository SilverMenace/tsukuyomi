package dev.tsukuyomi.schema;

/** The trigger that caps how many rows a table may hold (see V5__row_quota.sql). */
public final class RowQuota {

	/** SQLSTATE raised by tsk.enforce_row_quota(). */
	public static final String SQL_STATE = "TSK01";

	private RowQuota() {
	}

	/** @param qualifiedTable the quoted "schema"."table" name */
	public static String statement(String qualifiedTable, int maxRows) {
		// maxRows is an int from our own configuration, never request text.
		return "create trigger \"tsk_quota\" after insert on " + qualifiedTable
				+ " for each statement execute function tsk.enforce_row_quota('" + maxRows + "')";
	}

}
