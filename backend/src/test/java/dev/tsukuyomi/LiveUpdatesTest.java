package dev.tsukuyomi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.tsukuyomi.support.LiveTestClient;
import dev.tsukuyomi.support.TestClient;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantTransaction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live updates over a real WebSocket against a real Postgres: who hears about
 * a change, who must not, and whether a client that was away gets what it missed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveUpdatesTest {

	/** notes: private to their owner. posts: anyone may read, the service key writes. */
	private static final String SCHEMA = """
			{"operations":[
			 {"op":"create_table","table":"notes","columns":[
			   {"name":"title","type":"text"},
			   {"name":"owner_id","type":"uuid","nullable":false,"references":"users","onDelete":"cascade"}]},
			 {"op":"set_rule","table":"notes","operation":"select","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"insert","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"update","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"delete","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"create_table","table":"posts","columns":[{"name":"body","type":"text"}]},
			 {"op":"set_rule","table":"posts","operation":"select","rule":"anyone"}
			]}""";

	@LocalServerPort
	int port;

	@Autowired
	TenantTransaction tenant;

	@Autowired
	JdbcTemplate ownerJdbc;

	@Autowired
	dev.tsukuyomi.live.EventLog eventLog;

	private ProjectRef project;

	private String serviceKey;

	private String anonKey;

	private String otherServiceKey;

	private TestClient service;

	private TestClient alice;

	private String aliceToken;

	private String bobToken;

	private static String email(String name) {
		return name + "-" + UUID.randomUUID() + "@example.com";
	}

	private JsonNode createProject(String name) {
		TestClient dashboard = new TestClient(port);
		dashboard.signup(email("owner"));
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"" + name + "\"}").json();
		String base = "/platform/v1/projects/" + created.get("project").get("id").asString();
		TestClient.Response migrated = dashboard.post(base + "/migrations", SCHEMA);
		assertThat(migrated.status()).as(migrated.body()).isEqualTo(200);
		return created;
	}

	private String signup(TestClient anon, String name) {
		TestClient.Response response = anon.post("/auth/v1/signup",
				"{\"email\":\"" + email(name) + "\",\"password\":\"correct horse battery\"}");
		assertThat(response.status()).as(response.body()).isEqualTo(201);
		return response.json().get("access_token").asString();
	}

	@BeforeAll
	void setUp() {
		JsonNode created = createProject("live");
		project = new ProjectRef(UUID.fromString(created.get("project").get("id").asString()),
				created.get("project").get("schemaName").asString());
		serviceKey = created.get("serviceKey").asString();
		anonKey = created.get("anonKey").asString();
		service = new TestClient(port).withApiKey(serviceKey);
		TestClient anon = new TestClient(port).withApiKey(anonKey);
		aliceToken = signup(anon, "alice");
		bobToken = signup(anon, "bob");
		alice = anon.withBearer(aliceToken);
		otherServiceKey = createProject("other").get("serviceKey").asString();
	}

	private static void assertEvent(JsonNode message, String op, String table, String rowId) {
		assertThat(message).as("an event should have arrived").isNotNull();
		assertThat(message.get("type").asString()).as(message.toString()).isEqualTo("event");
		assertThat(message.get("op").asString()).isEqualTo(op);
		assertThat(message.get("table").asString()).isEqualTo(table);
		assertThat(message.get("id").asString()).isEqualTo(rowId);
	}

	// ---- permissions ---------------------------------------------------------

	@Test
	void aPrivateRowIsAnnouncedOnlyToItsOwnerAndTheServiceKey() {
		try (LiveTestClient aliceLive = LiveTestClient.connect(port, anonKey, aliceToken);
				LiveTestClient bobLive = LiveTestClient.connect(port, anonKey, bobToken);
				LiveTestClient anonLive = LiveTestClient.connect(port, anonKey, null);
				LiveTestClient serviceLive = LiveTestClient.connect(port, serviceKey, null)) {
			for (LiveTestClient client : List.of(aliceLive, bobLive, anonLive, serviceLive)) {
				assertThat(client.subscribe("notes", null).get("type").asString()).isEqualTo("subscribed");
			}

			String id = alice.post("/api/v1/notes", "{\"title\":\"alice's secret\"}").json().get("id").asString();

			JsonNode seen = aliceLive.next();
			assertEvent(seen, "insert", "notes", id);
			assertThat(seen.get("row").get("title").asString()).isEqualTo("alice's secret");
			assertEvent(serviceLive.next(), "insert", "notes", id);

			alice.patch("/api/v1/notes?id=eq." + id, "{\"title\":\"edited\"}");
			JsonNode update = aliceLive.next();
			assertEvent(update, "update", "notes", id);
			assertThat(update.get("row").get("title").asString()).isEqualTo("edited");
			assertEvent(serviceLive.next(), "update", "notes", id);

			// A delete carries the id and no row.
			alice.delete("/api/v1/notes?id=eq." + id);
			JsonNode delete = aliceLive.next();
			assertEvent(delete, "delete", "notes", id);
			assertThat(delete.get("row").isNull()).isTrue();
			assertEvent(serviceLive.next(), "delete", "notes", id);

			// Bob and the anonymous visitor are subscribed to the same table and heard
			// nothing at all: not the insert, not the update, and not even the delete.
			assertThat(bobLive.next(700)).isNull();
			assertThat(anonLive.next(100)).isNull();
		}
	}

	@Test
	void aPublicRowIsAnnouncedToEveryone() {
		try (LiveTestClient anonLive = LiveTestClient.connect(port, anonKey, null);
				LiveTestClient bobLive = LiveTestClient.connect(port, anonKey, bobToken)) {
			anonLive.subscribe("posts", null);
			bobLive.subscribe("posts", null);
			String id = service.post("/api/v1/posts", "{\"body\":\"hello\"}").json().get("id").asString();
			assertEvent(anonLive.next(), "insert", "posts", id);
			assertEvent(bobLive.next(), "insert", "posts", id);
			service.delete("/api/v1/posts?id=eq." + id);
			assertEvent(anonLive.next(), "delete", "posts", id);
			assertEvent(bobLive.next(), "delete", "posts", id);
		}
	}

	@Test
	void anotherProjectWithTheSameTableNameHearsNothing() {
		try (LiveTestClient mine = LiveTestClient.connect(port, serviceKey, null);
				LiveTestClient other = LiveTestClient.connect(port, otherServiceKey, null)) {
			mine.subscribe("posts", null);
			other.subscribe("posts", null);
			String id = service.post("/api/v1/posts", "{\"body\":\"ours\"}").json().get("id").asString();
			assertEvent(mine.next(), "insert", "posts", id);
			// Even the other project's service key, the most powerful identity it has, gets nothing.
			assertThat(other.next(700)).isNull();
		}
	}

	// ---- the protocol's doors ------------------------------------------------

	@Test
	void nothingWorksBeforeAuthentication() {
		try (LiveTestClient client = new LiveTestClient(port)) {
			client.send("{\"type\":\"subscribe\",\"table\":\"posts\"}");
			assertThat(client.next().get("code").asString()).isEqualTo("unauthorized");
			client.send("not json");
			assertThat(client.next().get("code").asString()).isEqualTo("bad_message");
		}
	}

	@Test
	void aWrongKeyOrAForgedTokenClosesTheConnection() throws InterruptedException {
		try (LiveTestClient wrongKey = new LiveTestClient(port); LiveTestClient forged = new LiveTestClient(port)) {
			wrongKey.send("{\"type\":\"auth\",\"apikey\":\"tsk_anon_0000000000000000000000000000000000000000\"}");
			assertThat(wrongKey.next().get("code").asString()).isEqualTo("invalid_key");

			// A token that is valid in another sense (right shape) but tampered with is
			// rejected, not quietly treated as anonymous.
			forged.send("{\"type\":\"auth\",\"apikey\":\"" + anonKey + "\",\"token\":\""
					+ aliceToken.substring(0, aliceToken.length() - 4) + "AAAA\"}");
			assertThat(forged.next().get("code").asString()).isEqualTo("invalid_token");

			for (int i = 0; i < 50 && (wrongKey.closeCode() < 0 || forged.closeCode() < 0); i++) {
				Thread.sleep(100);
			}
			assertThat(wrongKey.closeCode()).isEqualTo(4401);
			assertThat(forged.closeCode()).isEqualTo(4401);
		}
	}

	@Test
	void onlyExistingTablesCanBeSubscribedTo() {
		try (LiveTestClient client = LiveTestClient.connect(port, anonKey, null)) {
			assertThat(client.subscribe("nope", null).get("code").asString()).isEqualTo("unknown_table");
			assertThat(client.subscribe("posts; drop table posts; --", null).get("code").asString())
				.isEqualTo("unknown_table");
			assertThat(client.subscribe("posts", "not-a-number").get("code").asString()).isEqualTo("bad_message");
			assertThat(client.subscribe("posts", null).get("type").asString()).isEqualTo("subscribed");
			assertThat(client.subscribe("posts", null).get("code").asString()).isEqualTo("already_subscribed");
		}
	}

	/**
	 * A WebSocket proves who it is once, when it connects. So replacing a key
	 * must also end the connections that were let in with the old one.
	 */
	@Test
	void replacingAKeyClosesConnectionsOpenedWithTheOldOne() throws InterruptedException {
		TestClient dashboard = new TestClient(port);
		dashboard.signup(email("rotating-owner"));
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"rotate\"}").json();
		String base = "/platform/v1/projects/" + created.get("project").get("id").asString();
		try (LiveTestClient live = LiveTestClient.connect(port, created.get("serviceKey").asString(), null)) {
			assertThat(live.subscribe("users", null).get("type").asString()).isEqualTo("subscribed");
			assertThat(dashboard.post(base + "/keys/service/rotate", "{}").status()).isEqualTo(200);
			assertThat(live.next().get("code").asString()).isEqualTo("access_changed");
			for (int i = 0; i < 50 && live.closeCode() < 0; i++) {
				Thread.sleep(100);
			}
			assertThat(live.closeCode()).isEqualTo(4401);
		}
	}

	// ---- not missing changes -------------------------------------------------

	/** Reads events until none arrives for a moment and returns the row ids, in order. */
	private static List<String> drainIds(LiveTestClient client) {
		List<String> ids = new ArrayList<>();
		for (JsonNode message = client.next(1500); message != null; message = client.next(700)) {
			if ("event".equals(message.get("type").asString())) {
				ids.add(message.get("id").asString());
			}
		}
		return ids;
	}

	@Test
	void aClientThatWasAwayGetsWhatItMissed() {
		String cursor;
		try (LiveTestClient first = LiveTestClient.connect(port, serviceKey, null)) {
			first.subscribe("posts", null);
			service.post("/api/v1/posts", "{\"body\":\"seen\"}");
			cursor = first.next().get("cursor").asString();
		}
		// Keep one subscriber connected, as a busy server would have.
		try (LiveTestClient other = LiveTestClient.connect(port, serviceKey, null)) {
			other.subscribe("notes", null);
			String missed1 = service.post("/api/v1/posts", "{\"body\":\"missed 1\"}").json().get("id").asString();
			String missed2 = service.post("/api/v1/posts", "{\"body\":\"missed 2\"}").json().get("id").asString();

			try (LiveTestClient back = LiveTestClient.connect(port, serviceKey, null)) {
				JsonNode subscribed = back.subscribe("posts", cursor);
				assertThat(subscribed.get("resync").asBoolean()).isFalse();
				List<String> ids = drainIds(back);
				// At-least-once: the last event seen before the break may come again, but
				// both missed ones are there, in order, and each only once.
				assertThat(ids).containsSubsequence(missed1, missed2);
				assertThat(ids.stream().filter(missed1::equals).count()).isEqualTo(1);

				// And the connection is live again afterwards.
				String live = service.post("/api/v1/posts", "{\"body\":\"live\"}").json().get("id").asString();
				assertEvent(back.next(), "insert", "posts", live);
			}
		}
	}

	/**
	 * The subtle one. Event ids are handed out when a row is written, not when
	 * its transaction commits. Transaction A writes first (lower id) but commits
	 * last. A client that saw B and then lost its connection must still get A
	 * when it comes back. A cursor based on "last event id" would skip A for ever.
	 */
	@Test
	void aChangeThatCommitsLateIsNotSkippedOnReplay() throws Exception {
		CountDownLatch written = new CountDownLatch(1);
		CountDownLatch mayCommit = new CountDownLatch(1);
		UUID slowId = UUID.randomUUID();
		Thread slow = Thread.ofVirtual().start(() -> tenant.execute(TenantIdentity.service(project), jdbc -> {
			jdbc.update("insert into posts (id, body) values (?, 'A: written first, committed last')", slowId);
			written.countDown();
			try {
				mayCommit.await(20, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
			return null;
		}));
		assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

		String cursor;
		String fastId;
		try (LiveTestClient first = LiveTestClient.connect(port, serviceKey, null)) {
			first.subscribe("posts", null);
			fastId = service.post("/api/v1/posts", "{\"body\":\"B: written second, committed first\"}")
				.json().get("id").asString();
			JsonNode event = first.next();
			assertEvent(event, "insert", "posts", fastId);
			cursor = event.get("cursor").asString();
		}

		// A commits while the client is away.
		mayCommit.countDown();
		slow.join();

		try (LiveTestClient back = LiveTestClient.connect(port, serviceKey, null)) {
			back.subscribe("posts", cursor);
			assertThat(drainIds(back)).contains(slowId.toString());
		}
	}

	@Test
	void aCursorOlderThanTheLogMeansReload() {
		String cursor;
		try (LiveTestClient first = LiveTestClient.connect(port, serviceKey, null)) {
			cursor = first.subscribe("posts", null).get("cursor").asString();
			service.post("/api/v1/posts", "{\"body\":\"will be forgotten\"}");
			first.next();
		}
		// Pretend ten minutes passed: everything in the log is deleted.
		assertThat(prune()).isPositive();

		try (LiveTestClient back = LiveTestClient.connect(port, serviceKey, null)) {
			JsonNode subscribed = back.subscribe("posts", cursor);
			assertThat(subscribed.get("resync").asBoolean()).isTrue();
			// It still works from here on.
			String id = service.post("/api/v1/posts", "{\"body\":\"after\"}").json().get("id").asString();
			assertEvent(back.next(), "insert", "posts", id);
		}
	}

	/**
	 * The server's own listening connection dies (the free database restarts or
	 * goes to sleep). Notifications sent meanwhile are gone, so the server must
	 * say so instead of pretending nothing happened, and then carry on.
	 */
	@Test
	void whenTheServerLosesItsFeedClientsAreToldToReload() {
		try (LiveTestClient client = LiveTestClient.connect(port, serviceKey, null)) {
			client.subscribe("posts", null);
			Integer killed = ownerJdbc.queryForObject("""
					select count(*) from (
					  select pg_terminate_backend(pid) from pg_stat_activity
					  where application_name = 'tsukuyomi-live') k""", Integer.class);
			assertThat(killed).isEqualTo(1);

			JsonNode resync = client.next(10_000);
			assertThat(resync).isNotNull();
			assertThat(resync.get("type").asString()).isEqualTo("resync");
			assertThat(resync.get("table").asString()).isEqualTo("posts");

			String id = service.post("/api/v1/posts", "{\"body\":\"after the break\"}").json().get("id").asString();
			assertEvent(client.next(), "insert", "posts", id);
		}
	}

	private int prune() {
		return org.springframework.test.util.ReflectionTestUtils.invokeMethod(eventLog, "prune", Duration.ZERO);
	}

}
