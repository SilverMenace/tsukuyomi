package dev.tsukuyomi.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A WebSocket client for tests: sends JSON text, collects every message received in a queue. */
public class LiveTestClient implements WebSocket.Listener, AutoCloseable {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

	private final StringBuilder partial = new StringBuilder();

	private final WebSocket socket;

	private volatile int closeCode = -1;

	public LiveTestClient(int port) {
		this.socket = HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.buildAsync(URI.create("ws://localhost:" + port + "/realtime/v1"), this)
			.join();
	}

	/** Connects, authenticates and waits for "ready". */
	public static LiveTestClient connect(int port, String apiKey, String token) {
		LiveTestClient client = new LiveTestClient(port);
		client.send("{\"type\":\"auth\",\"apikey\":\"" + apiKey + "\""
				+ (token == null ? "" : ",\"token\":\"" + token + "\"") + "}");
		JsonNode ready = client.next();
		if (ready == null || !"ready".equals(ready.get("type").asString())) {
			throw new AssertionError("expected ready, got " + ready);
		}
		return client;
	}

	public void send(String json) {
		socket.sendText(json, true).join();
	}

	/** Subscribes and returns the "subscribed" answer. */
	public JsonNode subscribe(String table, String since) {
		send("{\"type\":\"subscribe\",\"table\":\"" + table + "\""
				+ (since == null ? "" : ",\"since\":\"" + since + "\"") + "}");
		return next();
	}

	/** The next message, or null if none arrives within 5 seconds. */
	public JsonNode next() {
		return next(5000);
	}

	public JsonNode next(long millis) {
		try {
			return received.poll(millis, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The close code sent by the server, or -1 while open. */
	public int closeCode() {
		return closeCode;
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		partial.append(data);
		if (last) {
			received.add(JSON.readTree(partial.toString()));
			partial.setLength(0);
		}
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		closeCode = statusCode;
		return null;
	}

	@Override
	public void close() {
		socket.abort();
	}

}
