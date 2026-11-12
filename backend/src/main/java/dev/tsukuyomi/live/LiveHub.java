package dev.tsukuyomi.live;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tsukuyomi.data.RowService;
import dev.tsukuyomi.schema.CatalogService;
import dev.tsukuyomi.schema.Rule;
import dev.tsukuyomi.schema.RuleOperation;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The middle of live updates: knows who is subscribed to what, and turns each
 * change in the database into messages for the clients that may see it.
 *
 * WHO MAY SEE A CHANGE. For an insert or update the hub does not look at
 * permission rules at all. It reads the row once per distinct identity
 * subscribed to that table (RowService.findById, which runs as that identity's
 * Postgres role). If row-level security hides the row, the read returns
 * nothing and those clients are told nothing. A deleted row cannot be read any
 * more; for deletes only, the hub applies the table's read rule itself to the
 * copy of the row the trigger kept (couldSeeDeletedRow), and sends just the id.
 *
 * ONE THREAD. Everything here runs on a single "dispatcher" thread: changes,
 * subscribe, unsubscribe, disconnect. Other threads only put a task in its
 * queue. So the maps below need no locks, and clients receive changes in the
 * order the database committed them.
 *
 * NOT MISSING CHANGES. Every message carries a "cursor". A client that
 * reconnects sends back the last cursor it saw and gets the changes it missed
 * from the log. The cursor is not an event id, because ids are handed out when
 * a row is written, not when its transaction commits: event 5 can commit after
 * event 7 was delivered, and "everything after 7" would skip it for ever.
 * Instead each event records xmin, the oldest transaction still running when
 * it was written. Everything older than xmin had finished, so it committed
 * (and was delivered) before this event. "Send me changes from transactions
 * >= cursor" therefore cannot skip anything. It can repeat a change the client
 * already has, so clients ignore event ids they have seen. (DECISIONS.md D33)
 */
@Component
public class LiveHub implements ChangeListener.Sink, DisposableBean {

	static final int MAX_SUBSCRIPTIONS_PER_CLIENT = 20;

	/** More missed changes than this and the client is told to reload instead. */
	static final int REPLAY_LIMIT = 200;

	/** If the dispatcher falls this far behind, drop the backlog and ask everyone to reload. */
	private static final int MAX_QUEUE = 10_000;

	private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

	private record Topic(String schema, String table) {
	}

	/** One client's interest in one table. */
	static final class Subscription {

		final LiveClient client;

		final String table;

		long cursor;

		/** Events sent during replay whose live notification may still arrive: skip them once. */
		final Set<Long> replayed = new HashSet<>();

		Subscription(LiveClient client, String table, long cursor) {
			this.client = client;
			this.table = table;
			this.cursor = cursor;
		}

	}

	private final ChangeListener listener;

	private final EventLog events;

	private final RowService rows;

	private final CatalogService catalogs;

	private final ObjectMapper json;

	private final ExecutorService dispatcher = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("live-dispatch").daemon().factory());

	private final AtomicInteger queued = new AtomicInteger();

	private final AtomicBoolean overflowed = new AtomicBoolean();

	// ---- state below is touched only by the dispatcher thread ----

	/** table -> identity -> subscriptions. Grouped by identity so each identity reads the row once. */
	private final Map<Topic, Map<TenantIdentity, List<Subscription>>> topics = new HashMap<>();

	private int subscriptionCount;

	/** One-entry cache for deletedOwner. */
	private long ownerOfEvent = -1;

	private String owner;

	LiveHub(ChangeListener listener, EventLog events, RowService rows, CatalogService catalogs, ObjectMapper json) {
		this.catalogs = catalogs;
		this.listener = listener;
		this.events = events;
		this.rows = rows;
		this.json = json;
	}

	// ---- called from other threads: each only queues a task ----

	/** @param since the cursor from an earlier connection, or null to start from now */
	void subscribe(LiveClient client, String table, Long since) {
		submit(() -> doSubscribe(client, table, since));
	}

	void unsubscribe(LiveClient client, String table) {
		submit(() -> {
			Subscription subscription = client.subscriptions.remove(table);
			if (subscription != null) {
				remove(subscription);
				client.send(client.message("unsubscribed").put("table", table));
			}
			stopListeningIfIdle();
		});
	}

	void disconnect(LiveClient client) {
		submit(() -> drop(client));
	}

	@Override
	public void onChange(String payload) {
		if (queued.get() >= MAX_QUEUE) {
			overflowed.set(true); // too far behind: this change is dropped, clients will be told to reload
			return;
		}
		submit(() -> dispatch(ChangeEvent.parse(json.readTree(payload))));
	}

	@Override
	public void onFeedRestored() {
		submit(this::resyncAll);
	}

	private void submit(Runnable task) {
		queued.incrementAndGet();
		dispatcher.execute(() -> {
			queued.decrementAndGet();
			try {
				if (overflowed.compareAndSet(true, false)) {
					resyncAll();
				}
				task.run();
			}
			catch (RuntimeException e) {
				log.warn("live update task failed", e);
			}
		});
	}

	// ---- everything below runs on the dispatcher thread ----

	private void doSubscribe(LiveClient client, String table, Long since) {
		TenantIdentity identity = client.identity();
		if (!client.isOpen() || identity == null) {
			return;
		}
		if (client.subscriptions.containsKey(table)) {
			client.error("already_subscribed", "already subscribed to " + table);
			return;
		}
		if (client.subscriptions.size() >= MAX_SUBSCRIPTIONS_PER_CLIENT) {
			client.error("too_many_subscriptions", "at most " + MAX_SUBSCRIPTIONS_PER_CLIENT + " tables per connection");
			return;
		}
		String schema = identity.project().schemaName();
		List<ChangeEvent> missed = List.of();
		boolean resync = false;
		long cursor;
		try {
			// Listen BEFORE choosing the starting point, so nothing can commit in between unheard.
			listener.start(this);
			if (since == null) {
				cursor = events.currentXmin();
			}
			else {
				missed = events.since(schema, table, since, REPLAY_LIMIT + 1);
				// Read the floor after the events: if it is still at or below the
				// cursor, nothing the client needs had been deleted when we read.
				if (missed.size() > REPLAY_LIMIT || events.floor() > since) {
					missed = List.of();
					resync = true;
					cursor = events.currentXmin();
				}
				else {
					cursor = since;
				}
			}
		}
		catch (SQLException | RuntimeException e) {
			log.warn("cannot start live updates: {}", e.getMessage());
			client.error("unavailable", "live updates are not available right now, try again");
			stopListeningIfIdle();
			return;
		}

		Subscription subscription = new Subscription(client, table, cursor);
		client.subscriptions.put(table, subscription);
		topics.computeIfAbsent(new Topic(schema, table), t -> new HashMap<>())
			.computeIfAbsent(identity, i -> new ArrayList<>())
			.add(subscription);
		subscriptionCount++;
		// resync=true means: we cannot tell you what you missed, load the table again.
		client.send(client.message("subscribed").put("table", table).put("cursor", Long.toString(cursor))
			.put("resync", resync));

		for (ChangeEvent event : missed) {
			deliver(event, identity, List.of(subscription));
			subscription.replayed.add(event.id());
		}
	}

	private void dispatch(ChangeEvent event) {
		events.markDirty();
		Map<TenantIdentity, List<Subscription>> groups = topics.get(new Topic(event.schema(), event.table()));
		if (groups == null) {
			return;
		}
		// Copy: delivering can drop a client, which changes the map.
		for (Map.Entry<TenantIdentity, List<Subscription>> group : List.copyOf(groups.entrySet())) {
			List<Subscription> fresh = new ArrayList<>();
			for (Subscription subscription : List.copyOf(group.getValue())) {
				if (!subscription.replayed.remove(event.id())) {
					fresh.add(subscription);
				}
			}
			if (!fresh.isEmpty()) {
				deliver(event, group.getKey(), fresh);
			}
		}
	}

	/** Reads the row as {@code identity} and, if it is visible, sends the event to the subscriptions. */
	private void deliver(ChangeEvent event, TenantIdentity identity, List<Subscription> subscriptions) {
		JsonNode row = null;
		if (event.isDelete()) {
			if (!couldSeeDeletedRow(event, identity)) {
				return;
			}
		}
		else {
			try {
				row = rows.findById(identity, event.table(), event.rowId());
			}
			catch (RuntimeException e) {
				log.debug("live update read failed for {}: {}", event.table(), e.getMessage());
				return; // e.g. the table was dropped meanwhile
			}
			if (row == null) {
				return; // this identity may not see the row (or it is gone again)
			}
		}
		Instant now = Instant.now();
		for (Subscription subscription : subscriptions) {
			LiveClient client = subscription.client;
			if (client.expired(now)) {
				client.error("token_expired", "the access token has expired; connect again with a new one");
				client.close(LiveClient.UNAUTHORIZED);
				drop(client);
				continue;
			}
			subscription.cursor = Math.max(subscription.cursor, event.xmin());
			ObjectNode message = client.message("event")
				.put("table", event.table())
				.put("op", event.op())
				.put("id", event.rowId().toString())
				.put("eventId", Long.toString(event.id()))
				.put("cursor", Long.toString(subscription.cursor));
			message.set("row", row); // null for deletes
			if (!client.send(message)) {
				drop(client);
			}
		}
	}

	/**
	 * A deleted row cannot be read any more, so row-level security cannot answer
	 * "could this identity see it?". This is the one place where the hub applies
	 * the table's read rule itself, using the copy of the row the trigger kept.
	 * It mirrors the policies in MigrationPlanner and, when unsure, says no.
	 */
	private boolean couldSeeDeletedRow(ChangeEvent event, TenantIdentity identity) {
		if (identity.role() == TenantRole.SERVICE) {
			return true;
		}
		Rule rule;
		try {
			rule = catalogs.get(identity.project().id())
				.table(event.table())
				.map(table -> table.rule(RuleOperation.SELECT))
				.orElse(Rule.NOBODY);
		}
		catch (RuntimeException e) {
			return false; // e.g. the project was deleted meanwhile
		}
		return switch (rule.kind()) {
			case NOBODY -> false;
			case ANYONE -> true;
			case AUTHENTICATED -> identity.role() == TenantRole.USER;
			case OWNER -> identity.role() == TenantRole.USER
					&& identity.userId().toString().equals(deletedOwner(event, rule.ownerColumn()));
		};
	}

	/** Looked up once per delete event, however many users are subscribed. */
	private String deletedOwner(ChangeEvent event, String ownerColumn) {
		if (ownerOfEvent != event.id()) {
			try {
				owner = events.deletedValue(event.id(), ownerColumn);
			}
			catch (RuntimeException e) {
				owner = null;
			}
			ownerOfEvent = event.id();
		}
		return owner;
	}

	/** We lost track of what happened (listener reconnect or overload): every client must reload. */
	private void resyncAll() {
		if (subscriptionCount == 0) {
			return;
		}
		long cursor;
		try {
			cursor = events.currentXmin();
		}
		catch (RuntimeException e) {
			log.warn("cannot recover live updates, closing all connections: {}", e.getMessage());
			for (LiveClient client : clients()) {
				client.close(org.springframework.web.socket.CloseStatus.SERVICE_RESTARTED);
				drop(client);
			}
			return;
		}
		for (LiveClient client : clients()) {
			for (Subscription subscription : client.subscriptions.values()) {
				subscription.cursor = cursor;
				subscription.replayed.clear();
				client.send(client.message("resync").put("table", subscription.table)
					.put("cursor", Long.toString(cursor)));
			}
		}
	}

	private Set<LiveClient> clients() {
		Set<LiveClient> clients = new HashSet<>();
		topics.values().forEach(groups -> groups.values().forEach(list -> list.forEach(s -> clients.add(s.client))));
		return clients;
	}

	private void drop(LiveClient client) {
		client.subscriptions.values().forEach(this::remove);
		client.subscriptions.clear();
		stopListeningIfIdle();
	}

	private void remove(Subscription subscription) {
		TenantIdentity identity = subscription.client.identity();
		Topic topic = new Topic(identity.project().schemaName(), subscription.table);
		Map<TenantIdentity, List<Subscription>> groups = topics.get(topic);
		if (groups == null) {
			return;
		}
		List<Subscription> list = groups.get(identity);
		if (list != null && list.remove(subscription)) {
			subscriptionCount--;
			if (list.isEmpty()) {
				groups.remove(identity);
			}
			if (groups.isEmpty()) {
				topics.remove(topic);
			}
		}
	}

	private void stopListeningIfIdle() {
		if (subscriptionCount == 0) {
			listener.stop();
		}
	}

	@Override
	public void destroy() {
		dispatcher.shutdownNow();
		listener.stop();
	}

}
