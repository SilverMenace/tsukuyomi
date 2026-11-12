package dev.tsukuyomi.live;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import dev.tsukuyomi.tenancy.TenantIdentity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** One WebSocket connection: who it is (after "auth") and what it subscribed to. */
final class LiveClient {

	/** Close code for "log in again". Codes 4000-4999 are free for applications to define. */
	static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "unauthorized");

	/** Give up on a client that cannot take a message within 5 s or has 256 KB waiting. */
	private static final int SEND_TIME_LIMIT_MS = 5000;

	private static final int SEND_BUFFER_BYTES = 256 * 1024;

	private final WebSocketSession session;

	private final ObjectMapper json;

	private volatile TenantIdentity identity;

	private volatile Instant expiresAt;

	/** By table name. Touched only by LiveHub's dispatcher thread. */
	final Map<String, LiveHub.Subscription> subscriptions = new HashMap<>();

	LiveClient(WebSocketSession session, ObjectMapper json) {
		// The decorator makes sending safe from several threads (the dispatcher
		// sends events while a request thread may send an error).
		this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
		this.json = json;
	}

	void authenticate(TenantIdentity identity, Instant expiresAt) {
		this.identity = identity;
		this.expiresAt = expiresAt;
	}

	/** Null until the client has sent a valid "auth" message. */
	TenantIdentity identity() {
		return identity;
	}

	boolean expired(Instant now) {
		Instant end = expiresAt;
		return end != null && now.isAfter(end);
	}

	boolean isOpen() {
		return session.isOpen();
	}

	ObjectNode message(String type) {
		return json.createObjectNode().put("type", type);
	}

	/** @return false if the message could not be sent; the connection is then closed */
	boolean send(ObjectNode message) {
		try {
			session.sendMessage(new TextMessage(json.writeValueAsString(message)));
			return true;
		}
		catch (IOException | RuntimeException e) {
			close(CloseStatus.SESSION_NOT_RELIABLE);
			return false;
		}
	}

	void ping() {
		try {
			session.sendMessage(new org.springframework.web.socket.PingMessage());
		}
		catch (IOException | RuntimeException e) {
			close(CloseStatus.SESSION_NOT_RELIABLE);
		}
	}

	void error(String code, String text) {
		send(message("error").put("code", code).put("message", text));
	}

	void close(CloseStatus status) {
		try {
			session.close(status);
		}
		catch (IOException | RuntimeException e) {
			// already closed
		}
	}

}
