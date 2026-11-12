package dev.tsukuyomi.live;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.tsukuyomi.data.IdentityResolver;
import dev.tsukuyomi.platform.ProjectAccessChanged;
import dev.tsukuyomi.schema.CatalogService;
import dev.tsukuyomi.tenancy.TenantIdentity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The WebSocket endpoint /realtime/v1. The protocol is small JSON messages.
 *
 *   client                                      server
 *   {"type":"auth","apikey":K,"token":T?}       {"type":"ready","role":"user"}
 *   {"type":"subscribe","table":"notes",        {"type":"subscribed","table":"notes","cursor":"812","resync":false}
 *        "since":"790"?}                        {"type":"event","table":"notes","op":"insert","id":ROW_ID,
 *                                                "row":{...},"eventId":"41","cursor":"815"}   (many)
 *   {"type":"unsubscribe","table":"notes"}      {"type":"unsubscribed","table":"notes"}
 *   {"type":"ping"}                             {"type":"pong"}
 *                                               {"type":"resync","table":"notes","cursor":"900"}
 *                                               {"type":"error","code":"...","message":"..."}
 *
 * Browsers cannot set headers on a WebSocket, so the API key and token arrive
 * in the first message instead of in "apikey" and "Authorization" headers.
 * They are turned into an identity by the same IdentityResolver as HTTP requests.
 */
@Component
class LiveSocketHandler extends TextWebSocketHandler implements DisposableBean {

	static final int MAX_CONNECTIONS = 300;

	static final int MAX_CONNECTIONS_PER_PROJECT = 100;

	private static final int MAX_MESSAGE_BYTES = 4096;

	private static final int AUTH_WITHIN_SECONDS = 10;

	private static final CloseStatus TOO_MANY = new CloseStatus(1013, "too many connections");

	private final Map<String, LiveClient> clients = new ConcurrentHashMap<>();

	private final ScheduledExecutorService timer = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("live-timer").daemon().factory());

	private final int maxPerAddress;

	private final LiveHub hub;

	private final IdentityResolver identities;

	private final CatalogService catalogs;

	private final ObjectMapper json;

	LiveSocketHandler(LiveHub hub, IdentityResolver identities, CatalogService catalogs, ObjectMapper json,
			@Value("${tsukuyomi.live.connections-per-address:20}") int maxPerAddress) {
		this.maxPerAddress = maxPerAddress;
		this.hub = hub;
		this.identities = identities;
		this.catalogs = catalogs;
		this.json = json;
		// Proxies cut connections that stay silent; a ping every 25 s keeps them open.
		timer.scheduleWithFixedDelay(this::pingAll, 25, 25, TimeUnit.SECONDS);
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
		LiveClient client = new LiveClient(session, json);
		// Counted before authentication, so one machine cannot fill the server
		// with connections that never say who they are.
		long sameAddress = clients.values().stream().filter(c -> c.address.equals(client.address)).count();
		if (clients.size() >= MAX_CONNECTIONS || sameAddress >= maxPerAddress) {
			client.close(TOO_MANY);
			return;
		}
		clients.put(session.getId(), client);
		// A connection that never says who it is does not get to stay.
		timer.schedule(() -> {
			if (client.identity() == null) {
				client.close(LiveClient.UNAUTHORIZED);
			}
		}, AUTH_WITHIN_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage text) {
		LiveClient client = clients.get(session.getId());
		if (client == null) {
			return;
		}
		JsonNode message;
		try {
			message = json.readTree(text.getPayload());
		}
		catch (JacksonException e) {
			client.error("bad_message", "messages must be JSON objects");
			return;
		}
		String type = string(message, "type");
		if ("auth".equals(type)) {
			authenticate(client, message);
		}
		else if ("ping".equals(type)) {
			client.send(client.message("pong"));
		}
		else if (client.identity() == null) {
			client.error("unauthorized", "send an auth message first");
		}
		else if ("subscribe".equals(type)) {
			subscribe(client, message);
		}
		else if ("unsubscribe".equals(type) && string(message, "table") != null) {
			hub.unsubscribe(client, string(message, "table"));
		}
		else {
			client.error("bad_message", "unknown message type");
		}
	}

	private void authenticate(LiveClient client, JsonNode message) {
		if (client.identity() != null) {
			// The identity of a connection never changes; a new login means a new connection.
			client.error("already_authenticated", "this connection is already authenticated");
			return;
		}
		IdentityResolver.Caller caller = identities.resolve(string(message, "apikey"), string(message, "token"));
		switch (caller.status()) {
			case UNKNOWN_KEY -> reject(client, "invalid_key", "missing or invalid apikey");
			case INVALID_TOKEN -> reject(client, "invalid_token", "the access token is invalid or has expired");
			case OK -> {
				TenantIdentity identity = caller.identity();
				long sameProject = clients.values().stream()
					.filter(c -> c.identity() != null && c.identity().project().equals(identity.project()))
					.count();
				if (sameProject >= MAX_CONNECTIONS_PER_PROJECT) {
					client.error("too_many_connections", "this project has too many live connections");
					client.close(TOO_MANY);
					return;
				}
				client.authenticate(identity, caller.expiresAt());
				client.send(client.message("ready").put("role", identity.role().name().toLowerCase()));
			}
		}
	}

	private void reject(LiveClient client, String code, String text) {
		client.error(code, text);
		client.close(LiveClient.UNAUTHORIZED);
	}

	private void subscribe(LiveClient client, JsonNode message) {
		String table = string(message, "table");
		// The catalog is the list of tables that exist; the name is used only after it was found there.
		if (table == null || catalogs.get(client.identity().project().id()).table(table).isEmpty()) {
			client.error("unknown_table", "no such table");
			return;
		}
		Long since = null;
		String cursor = string(message, "since");
		if (cursor != null) {
			try {
				since = Long.parseLong(cursor);
			}
			catch (NumberFormatException e) {
				client.error("bad_message", "since must be a cursor from an earlier message");
				return;
			}
		}
		hub.subscribe(client, table, since);
	}

	/**
	 * A key was replaced or the project deleted. A WebSocket is checked only
	 * once, when it authenticates, so connections opened with the old
	 * credentials would otherwise keep receiving rows for ever. Close them all;
	 * clients with a key that is still good simply reconnect.
	 */
	@EventListener
	void onAccessChanged(ProjectAccessChanged change) {
		for (LiveClient client : clients.values()) {
			TenantIdentity identity = client.identity();
			if (identity != null && identity.project().id().equals(change.projectId())) {
				client.error("access_changed", "this project's keys changed; connect again");
				client.close(LiveClient.UNAUTHORIZED);
			}
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		LiveClient client = clients.remove(session.getId());
		if (client != null) {
			hub.disconnect(client);
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		// The connection is closed next; afterConnectionClosed cleans up.
	}

	private void pingAll() {
		for (Map.Entry<String, LiveClient> entry : clients.entrySet()) {
			entry.getValue().ping();
		}
	}

	/** The text of a field, or null if it is missing or not a string. */
	private static String string(JsonNode message, String field) {
		JsonNode value = message.get(field);
		return value != null && value.isString() ? value.stringValue() : null;
	}

	@Override
	public void destroy() {
		timer.shutdownNow();
	}

}
