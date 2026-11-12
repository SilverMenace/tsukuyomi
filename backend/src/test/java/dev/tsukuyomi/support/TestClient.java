package dev.tsukuyomi.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A tiny HTTP client for tests. It talks to the running app over real HTTP,
 * exactly as a browser or curl would, so the tests exercise the security
 * filters, JSON handling and controllers together.
 */
public class TestClient {

	public record Response(int status, String body, String setCookie) {

		public JsonNode json() {
			return new ObjectMapper().readTree(body);
		}

	}

	private final HttpClient http = HttpClient.newHttpClient();

	private final String baseUrl;

	private String cookie; // session cookie, when logged in to the dashboard

	private String apiKey; // apikey header, when calling the data API

	public TestClient(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	public TestClient withApiKey(String apiKey) {
		TestClient copy = new TestClient(baseUrl);
		copy.apiKey = apiKey;
		return copy;
	}

	private TestClient(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public Response get(String path) {
		return send("GET", path, null);
	}

	public Response post(String path, String json) {
		return send("POST", path, json);
	}

	public Response patch(String path, String json) {
		return send("PATCH", path, json);
	}

	public String apiKey() {
		return apiKey;
	}

	public Response delete(String path) {
		return send("DELETE", path, null);
	}

	/** Signs up a fresh dashboard account and keeps its session cookie. */
	public Response signup(String email) {
		return post("/platform/v1/accounts", "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}");
	}

	private Response send(String method, String path, String json) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
			request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(json));
			if (json != null) {
				request.header("Content-Type", "application/json");
			}
			if (cookie != null) {
				request.header("Cookie", cookie);
			}
			if (apiKey != null) {
				request.header("apikey", apiKey);
			}
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			String setCookie = response.headers().firstValue("Set-Cookie").orElse(null);
			if (setCookie != null) {
				String pair = setCookie.split(";", 2)[0];
				cookie = pair.endsWith("=") ? null : pair;
			}
			return new Response(response.statusCode(), response.body(), setCookie);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
