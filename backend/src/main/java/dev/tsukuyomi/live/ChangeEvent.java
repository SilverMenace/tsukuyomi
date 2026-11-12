package dev.tsukuyomi.live;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * "This row changed." One entry of tsk_platform.change_event, which is also
 * what the database sends with NOTIFY.
 *
 * It names the row but does not contain it. Whoever wants the row has to read
 * it under their own identity, so row-level security applies to live updates
 * exactly as it does to normal requests.
 *
 * @param txid the transaction that made the change
 * @param xmin the oldest transaction still running at that moment; see LiveHub
 */
public record ChangeEvent(long id, String schema, String table, String op, UUID rowId, long txid, long xmin) {

	public static final String DELETE = "delete";

	/** Parses the JSON payload built by the trigger function tsk.record_change(). */
	static ChangeEvent parse(JsonNode payload) {
		return new ChangeEvent(payload.get("id").asLong(), payload.get("schema").asString(),
				payload.get("table").asString(), payload.get("op").asString(),
				UUID.fromString(payload.get("row").asString()), payload.get("txid").asLong(),
				payload.get("xmin").asLong());
	}

	boolean isDelete() {
		return DELETE.equals(op);
	}

}
