package dev.tsukuyomi.live;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * Reads and trims the log of changes (tsk_platform.change_event). The log is
 * written only by the database trigger; the server uses it to answer "what
 * did I miss?" for clients that reconnect.
 */
@Repository
public class EventLog {

	/** How long changes stay available for replay. */
	static final Duration RETENTION = Duration.ofMinutes(10);

	private final JdbcTemplate jdbc;

	/** True when rows may have been written since the last clean-up. Starts true to clear leftovers. */
	private final AtomicBoolean dirty = new AtomicBoolean(true);

	EventLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The oldest transaction that is still running right now. Every transaction
	 * with a smaller id has finished, so its changes are already visible.
	 */
	long currentXmin() {
		return jdbc.queryForObject("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);
	}

	/** Changes to one table made by transactions with an id of at least {@code txid}, oldest first. */
	List<ChangeEvent> since(String schema, String table, long txid, int limit) {
		return jdbc.query("""
				select id, schema_name, table_name, op, row_id, txid, oldest_running
				from tsk_platform.change_event
				where schema_name = ? and table_name = ? and txid >= ?
				order by id limit ?""",
				(rs, n) -> new ChangeEvent(rs.getLong("id"), rs.getString("schema_name"), rs.getString("table_name"),
						rs.getString("op"), rs.getObject("row_id", UUID.class), rs.getLong("txid"), rs.getLong("oldest_running")),
				schema, table, txid, limit);
	}

	/**
	 * For a delete: the value the deleted row had in {@code column}, or null.
	 * Used with the owner column to find out whose row it was.
	 */
	String deletedValue(long eventId, String column) {
		return jdbc.query("select old_row ->> ? from tsk_platform.change_event where id = ?",
				rs -> rs.next() ? rs.getString(1) : null, column, eventId);
	}

	/** Changes from transactions below this id may have been deleted already. */
	long floor() {
		return jdbc.queryForObject("select txid from tsk_platform.change_floor", Long.class);
	}

	/** Called by code that writes rows, so the clean-up knows there may be something to do. */
	public void markDirty() {
		dirty.set(true);
	}

	/**
	 * Runs once a minute but touches the database only if something was written
	 * since last time. (A query every minute would keep the free database awake
	 * around the clock.)
	 */
	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	void pruneIfDirty() {
		if (dirty.compareAndSet(true, false)) {
			try {
				// Rows younger than the retention are still there, so look again later.
				if (prune(RETENTION) > 0 || count() > 0) {
					dirty.set(true);
				}
			}
			catch (RuntimeException e) {
				dirty.set(true);
			}
		}
	}

	/**
	 * Deletes changes older than {@code keep} and raises the floor past them, in
	 * one statement so that no reader can see one without the other.
	 * @return how many were deleted
	 */
	int prune(Duration keep) {
		Integer deleted = jdbc.queryForObject("""
				with gone as (
				    delete from tsk_platform.change_event
				    where created_at < clock_timestamp() - make_interval(secs => ?)
				    returning txid),
				raised as (
				    update tsk_platform.change_floor
				    set txid = greatest(txid, (select max(txid) + 1 from gone))
				    where exists (select 1 from gone))
				select count(*) from gone""", Integer.class, keep.toMillis() / 1000.0);
		return deleted == null ? 0 : deleted;
	}

	private long count() {
		return jdbc.queryForObject("select count(*) from (select 1 from tsk_platform.change_event limit 1) x",
				Long.class);
	}

}
