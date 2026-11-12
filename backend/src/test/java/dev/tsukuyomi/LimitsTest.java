package dev.tsukuyomi;

import java.util.UUID;

import dev.tsukuyomi.support.LiveTestClient;
import dev.tsukuyomi.support.TestClient;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Limits that protect a shared, free server from one greedy caller. Runs with
 * small numbers so the limits are reached quickly; each test pretends to come
 * from its own address (one trusted proxy is configured), so they do not
 * disturb each other.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "tsukuyomi.rate-limit.enabled=true", "tsukuyomi.rate-limit.trusted-proxies=1",
				"tsukuyomi.rate-limit.credentials-burst=4", "tsukuyomi.rate-limit.credentials-per-minute=1",
				"tsukuyomi.rate-limit.address-burst=30", "tsukuyomi.rate-limit.address-per-second=1",
				"tsukuyomi.rate-limit.key-burst=50", "tsukuyomi.rate-limit.key-per-second=1",
				"tsukuyomi.quota.rows-per-table=5", "tsukuyomi.live.connections-per-address=2", "tsukuyomi.max-body-bytes=2000" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LimitsTest {

	@LocalServerPort
	int port;

	private String anonKey;

	private String serviceKey;

	@BeforeAll
	void setUp() {
		TestClient dashboard = new TestClient(port).from("198.51.100.1");
		dashboard = withSession(dashboard, dashboard.signup("owner-" + UUID.randomUUID() + "@example.com"));
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"limits\"}").json();
		anonKey = created.get("anonKey").asString();
		serviceKey = created.get("serviceKey").asString();
		TestClient.Response migrated = dashboard.post(
				"/platform/v1/projects/" + created.get("project").get("id").asString() + "/migrations", """
						{"operations":[{"op":"create_table","table":"things","columns":[{"name":"n","type":"integer"}]},
						 {"op":"set_rule","table":"things","operation":"select","rule":"anyone"}]}""");
		assertThat(migrated.status()).as(migrated.body()).isEqualTo(200);
	}

	/** from() copies the client; the signup above stored its cookie on the copy it was called on. */
	private static TestClient withSession(TestClient client, TestClient.Response signup) {
		assertThat(signup.status()).as(signup.body()).isEqualTo(201);
		return client;
	}

	@Test
	void passwordGuessingFromOneAddressIsCutOff() {
		TestClient attacker = new TestClient(port).withApiKey(anonKey).from("203.0.113.10");
		String guess = "{\"email\":\"victim@example.com\",\"password\":\"guess number one\"}";
		for (int i = 0; i < 4; i++) {
			assertThat(attacker.post("/auth/v1/login", guess).status()).isEqualTo(401);
		}
		TestClient.Response refused = attacker.post("/auth/v1/login", guess);
		assertThat(refused.status()).isEqualTo(429);
		assertThat(refused.json().get("error").get("code").asString()).isEqualTo("rate_limited");
		assertThat(refused.json().get("error").get("message").asString()).contains("203.0.113.10");
		assertThat(Integer.parseInt(refused.header("Retry-After"))).isBetween(1, 60);
		// A browser app on another site must be able to read this answer.
		assertThat(refused.header("Access-Control-Allow-Origin")).isEqualTo("*");

		// The same limit guards the dashboard's own login.
		assertThat(attacker.post("/platform/v1/session", guess).status()).isEqualTo(429);
		// Ordinary reads from that address still work: only credentials were throttled.
		assertThat(attacker.get("/api/v1/things").status()).isEqualTo(200);
		// And someone else is not affected at all.
		assertThat(new TestClient(port).withApiKey(anonKey).from("203.0.113.11").post("/auth/v1/login", guess).status())
			.isEqualTo(401);
	}

	@Test
	void aFakeForwardedAddressDoesNotGiveAFreshAllowance() {
		String guess = "{\"email\":\"victim@example.com\",\"password\":\"guess number one\"}";
		int refused = 0;
		for (int i = 0; i < 8; i++) {
			// The attacker invents a new "client address" each time; the proxy
			// appends the real one (203.0.113.20) after it.
			TestClient attacker = new TestClient(port).withApiKey(anonKey).from("10.9.8." + i + ", 203.0.113.20");
			refused += attacker.post("/auth/v1/login", guess).status() == 429 ? 1 : 0;
		}
		assertThat(refused).isEqualTo(4);
	}

	@Test
	void oneAddressCannotFloodTheApi() {
		TestClient greedy = new TestClient(port).withApiKey(anonKey).from("203.0.113.30");
		int ok = 0;
		int limited = 0;
		for (int i = 0; i < 40; i++) {
			int status = greedy.get("/api/v1/things").status();
			ok += status == 200 ? 1 : 0;
			limited += status == 429 ? 1 : 0;
		}
		assertThat(ok).isBetween(30, 32); // the burst, plus what trickled back in meanwhile
		assertThat(ok + limited).isEqualTo(40);
	}

	@Test
	void oneProjectKeyIsLimitedAcrossAllAddresses() {
		int ok = 0;
		for (int i = 0; i < 70; i++) {
			// 70 different machines, the same project key.
			ok += new TestClient(port).withApiKey(serviceKey).from("192.0.2." + i).get("/api/v1/things").status() == 200
					? 1 : 0;
		}
		// The key's allowance is 50 (other tests in this class use the same key, so
		// some of it may be spent already). No single address came close to its own limit.
		assertThat(ok).isBetween(20, 53);
	}

	@Test
	void aTableStopsGrowingAtItsRowQuota() {
		TestClient service = new TestClient(port).withApiKey(serviceKey).from("203.0.113.40");
		// A batch that would cross the limit is refused as a whole.
		assertThat(service.post("/api/v1/things", "[{\"n\":1},{\"n\":2},{\"n\":3},{\"n\":4}]").status()).isEqualTo(201);
		TestClient.Response tooMany = service.post("/api/v1/things", "[{\"n\":5},{\"n\":6}]");
		assertThat(tooMany.status()).isEqualTo(409);
		assertThat(tooMany.json().get("error").get("code").asString()).isEqualTo("quota_rows");
		assertThat(service.post("/api/v1/things", "{\"n\":5}").status()).isEqualTo(201);
		assertThat(service.post("/api/v1/things", "{\"n\":6}").status()).isEqualTo(409);
		assertThat(service.get("/api/v1/things?select=n").json()).hasSize(5);
		// Deleting makes room again.
		service.delete("/api/v1/things?n=eq.1");
		assertThat(service.post("/api/v1/things", "{\"n\":6}").status()).isEqualTo(201);
	}

	/** The path is compared after decoding, the way the router sees it. */
	@Test
	void anEncodedPathDoesNotSlipPastTheCredentialLimit() {
		TestClient attacker = new TestClient(port).withApiKey(anonKey).from("203.0.113.60");
		String guess = "{\"email\":\"victim@example.com\",\"password\":\"guess number one\"}";
		int refused = 0;
		for (int i = 0; i < 8; i++) {
			// "%67" is "g": Spring routes this to the login handler.
			int status = attacker.post("/auth/v1/lo%67in", guess).status();
			assertThat(status).isIn(401, 429);
			refused += status == 429 ? 1 : 0;
		}
		assertThat(refused).isEqualTo(4);
	}

	/** A second header line added by the client must not replace what the proxy wrote. */
	@Test
	void aSecondForwardedHeaderLineIsCountedToo() throws Exception {
		java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
		int refused = 0;
		for (int i = 0; i < 8; i++) {
			java.net.http.HttpRequest request = java.net.http.HttpRequest
				.newBuilder(java.net.URI.create("http://localhost:" + port + "/auth/v1/login"))
				.header("X-Forwarded-For", "10.7.7." + i) // the client's own line, different every time
				.header("X-Forwarded-For", "203.0.113.70") // the line the proxy appends
				.header("apikey", anonKey)
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers
					.ofString("{\"email\":\"victim@example.com\",\"password\":\"guess number one\"}"))
				.build();
			refused += http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 429 ? 1
					: 0;
		}
		assertThat(refused).isEqualTo(4);
	}

	/** Ten inserts at once into a table with room for two: exactly two succeed. */
	@Test
	void theRowQuotaHoldsUnderConcurrentInserts() throws Exception {
		TestClient dashboard = new TestClient(port).from("198.51.100.2");
		dashboard.signup("owner-" + UUID.randomUUID() + "@example.com");
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"race\"}").json();
		dashboard.post("/platform/v1/projects/" + created.get("project").get("id").asString() + "/migrations",
				"{\"operations\":[{\"op\":\"create_table\",\"table\":\"things\",\"columns\":[{\"name\":\"n\",\"type\":\"integer\"}]}]}");
		String key = created.get("serviceKey").asString();
		TestClient filler = new TestClient(port).withApiKey(key).from("203.0.113.80");
		assertThat(filler.post("/api/v1/things", "[{\"n\":1},{\"n\":2},{\"n\":3}]").status()).isEqualTo(201);

		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger inserted = new java.util.concurrent.atomic.AtomicInteger();
		java.util.List<Thread> threads = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			TestClient writer = new TestClient(port).withApiKey(key).from("203.0.113." + (90 + i));
			threads.add(Thread.ofVirtual().start(() -> {
				try {
					start.await();
				}
				catch (InterruptedException e) {
					return;
				}
				if (writer.post("/api/v1/things", "{\"n\":9}").status() == 201) {
					inserted.incrementAndGet();
				}
			}));
		}
		start.countDown();
		for (Thread thread : threads) {
			thread.join();
		}
		assertThat(inserted.get()).isEqualTo(2);
		assertThat(filler.get("/api/v1/things?select=n").json()).hasSize(5);
	}

	/** No Content-Length to check: the limit must hold on what is actually read. */
	@Test
	void anOversizedBodyWithoutADeclaredLengthIsRefused() throws Exception {
		java.net.http.HttpRequest request = java.net.http.HttpRequest
			.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/things"))
			.header("X-Forwarded-For", "203.0.113.51")
			.header("apikey", serviceKey)
			.header("Content-Type", "application/json")
			// A stream of unknown length makes the client send the body in chunks.
			.POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
					("{\"n\":1," + " ".repeat(5000) + "\"x\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8))))
			.build();
		int status = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
			.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
		assertThat(status).isEqualTo(400);
		assertThat(new TestClient(port).withApiKey(serviceKey).from("203.0.113.52").get("/api/v1/things?n=eq.1&select=n")
			.status()).isEqualTo(200);
	}

	@Test
	void anOversizedBodyIsRefusedBeforeItIsRead() {
		TestClient service = new TestClient(port).withApiKey(serviceKey).from("203.0.113.50");
		TestClient.Response refused = service.post("/api/v1/things", "{\"n\":1," + " ".repeat(3000) + "\"x\":1}");
		assertThat(refused.status()).isEqualTo(413);
		assertThat(refused.json().get("error").get("code").asString()).isEqualTo("too_large");
	}

	@Test
	void oneAddressMayHoldOnlyAFewLiveConnections() throws InterruptedException {
		try (LiveTestClient first = new LiveTestClient(port); LiveTestClient second = new LiveTestClient(port);
				LiveTestClient third = new LiveTestClient(port)) {
			for (int i = 0; i < 50 && third.closeCode() < 0; i++) {
				Thread.sleep(100);
			}
			// Refused before it authenticated or sent anything.
			assertThat(third.closeCode()).isEqualTo(1013);
			assertThat(first.closeCode()).isEqualTo(-1);
			assertThat(second.closeCode()).isEqualTo(-1);
		}
	}

}
