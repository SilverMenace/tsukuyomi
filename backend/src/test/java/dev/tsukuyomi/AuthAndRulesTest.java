package dev.tsukuyomi;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import dev.tsukuyomi.support.TestClient;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantTransaction;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-user auth and permission rules, attacked from every side: other users,
 * anonymous callers, forged tokens, tokens from another project, and finally a
 * simulated bug in our own API layer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndRulesTest {

	/** notes: private to their owner. posts: anyone reads, logged-in users write their own. */
	private static final String SCHEMA = """
			{"operations":[
			 {"op":"create_table","table":"notes","columns":[
			   {"name":"title","type":"text"},
			   {"name":"owner_id","type":"uuid","nullable":false,"references":"users","onDelete":"cascade"}]},
			 {"op":"set_rule","table":"notes","operation":"select","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"insert","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"update","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"set_rule","table":"notes","operation":"delete","rule":"owner","ownerColumn":"owner_id"},
			 {"op":"create_table","table":"posts","columns":[
			   {"name":"body","type":"text"},
			   {"name":"author_id","type":"uuid","references":"users","onDelete":"cascade"}]},
			 {"op":"set_rule","table":"posts","operation":"select","rule":"anyone"},
			 {"op":"set_rule","table":"posts","operation":"insert","rule":"owner","ownerColumn":"author_id"},
			 {"op":"create_table","table":"locked","columns":[{"name":"secret","type":"text"}]}
			]}""";

	@LocalServerPort
	int port;

	@Autowired
	TenantTransaction tenant;

	@Autowired
	JdbcTemplate ownerJdbc;

	private TestClient dashboard;

	private String projectBase;

	private ProjectRef project;

	private TestClient service;

	private TestClient anon;

	private TestClient alice;

	private TestClient bob;

	private String aliceId;

	private String bobId;

	private String aliceToken;

	private String aliceNoteId;

	private String email(String name) {
		return name + "-" + UUID.randomUUID() + "@example.com";
	}

	private JsonNode signup(TestClient client, String email) {
		TestClient.Response response = client.post("/auth/v1/signup",
				"{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}");
		assertThat(response.status()).as(response.body()).isEqualTo(201);
		return response.json();
	}

	@BeforeAll
	void setUp() {
		dashboard = new TestClient(port);
		dashboard.signup(email("owner"));
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"rules\"}").json();
		String id = created.get("project").get("id").asString();
		projectBase = "/platform/v1/projects/" + id;
		project = new ProjectRef(UUID.fromString(id), created.get("project").get("schemaName").asString());
		TestClient.Response migrated = dashboard.post(projectBase + "/migrations", SCHEMA);
		assertThat(migrated.status()).as(migrated.body()).isEqualTo(200);

		service = new TestClient(port).withApiKey(created.get("serviceKey").asString());
		anon = new TestClient(port).withApiKey(created.get("anonKey").asString());
		JsonNode a = signup(anon, email("alice"));
		JsonNode b = signup(anon, email("bob"));
		aliceId = a.get("user").get("id").asString();
		bobId = b.get("user").get("id").asString();
		aliceToken = a.get("access_token").asString();
		alice = anon.withBearer(aliceToken);
		bob = anon.withBearer(b.get("access_token").asString());

		// Alice writes a private note and a public post. owner_id fills itself in.
		TestClient.Response note = alice.post("/api/v1/notes", "{\"title\":\"alice's secret\"}");
		assertThat(note.status()).as(note.body()).isEqualTo(201);
		assertThat(note.json().get("owner_id").asString()).isEqualTo(aliceId);
		aliceNoteId = note.json().get("id").asString();
		assertThat(alice.post("/api/v1/posts", "{\"body\":\"hello world\"}").status()).isEqualTo(201);
		service.post("/api/v1/locked", "{\"secret\":\"only the service key sees this\"}");
	}

	// ---- auth flows -----------------------------------------------------

	@Test
	void signupLoginRefreshLogout() {
		String email = email("carol");
		JsonNode tokens = signup(anon, email);
		assertThat(tokens.get("token_type").asString()).isEqualTo("bearer");
		assertThat(tokens.get("expires_in").asInt()).isEqualTo(3600);
		assertThat(anon.withBearer(tokens.get("access_token").asString()).get("/auth/v1/me").json().get("email").asString())
			.isEqualTo(email);

		String credentials = "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}";
		assertThat(anon.post("/auth/v1/signup", credentials).status()).isEqualTo(409);
		assertThat(anon.post("/auth/v1/login", credentials).status()).isEqualTo(200);
		assertThat(anon.post("/auth/v1/login", credentials.replace("correct", "wrong")).status()).isEqualTo(401);
		assertThat(anon.post("/auth/v1/login", credentials.replace(email, email("nobody"))).status()).isEqualTo(401);
		assertThat(anon.post("/auth/v1/signup", "{\"email\":\"not-an-email\",\"password\":\"long enough\"}").status())
			.isEqualTo(400);
		assertThat(anon.post("/auth/v1/signup", "{\"email\":\"" + email("short") + "\",\"password\":\"short\"}").status())
			.isEqualTo(400);

		// A refresh token works once, then is replaced.
		String refresh = "{\"refresh_token\":\"" + tokens.get("refresh_token").asString() + "\"}";
		TestClient.Response refreshed = anon.post("/auth/v1/refresh", refresh);
		assertThat(refreshed.status()).isEqualTo(200);
		assertThat(anon.post("/auth/v1/refresh", refresh).status()).isEqualTo(401);
		String next = "{\"refresh_token\":\"" + refreshed.json().get("refresh_token").asString() + "\"}";
		assertThat(anon.post("/auth/v1/logout", next).status()).isEqualTo(204);
		assertThat(anon.post("/auth/v1/refresh", next).status()).isEqualTo(401);

		assertThat(anon.get("/auth/v1/me").status()).isEqualTo(401); // no token
		assertThat(new TestClient(port).post("/auth/v1/login", credentials).status()).isEqualTo(401); // no apikey
		assertThat(dashboard.get(projectBase + "/users").json().size()).isGreaterThanOrEqualTo(3);
	}

	// ---- owner rules ----------------------------------------------------

	@Test
	void usersSeeOnlyTheirOwnRows() {
		assertThat(alice.get("/api/v1/notes").json().size()).isEqualTo(1);
		assertThat(bob.get("/api/v1/notes").body()).isEqualTo("[]");
		assertThat(anon.get("/api/v1/notes").body()).isEqualTo("[]");
		// asking for it by id does not help
		assertThat(bob.get("/api/v1/notes?id=eq." + aliceNoteId).body()).isEqualTo("[]");
		assertThat(bob.get("/api/v1/notes?owner_id=eq." + aliceId).body()).isEqualTo("[]");
		assertThat(service.get("/api/v1/notes").json().size()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void usersCannotChangeOrDeleteOthersRows() {
		// The rows are invisible to Bob, so his update and delete match nothing.
		assertThat(bob.patch("/api/v1/notes?id=eq." + aliceNoteId, "{\"title\":\"hacked\"}").body()).isEqualTo("[]");
		assertThat(bob.delete("/api/v1/notes?id=eq." + aliceNoteId).body()).isEqualTo("[]");
		assertThat(anon.patch("/api/v1/notes?id=eq." + aliceNoteId, "{\"title\":\"hacked\"}").body()).isEqualTo("[]");
		assertThat(anon.delete("/api/v1/notes?id=eq." + aliceNoteId).body()).isEqualTo("[]");
		assertThat(alice.get("/api/v1/notes?id=eq." + aliceNoteId).json().get(0).get("title").asString())
			.isEqualTo("alice's secret");
	}

	@Test
	void usersCannotCreateRowsForSomeoneElseOrGiveRowsAway() {
		// Bob inserts a note claiming Alice owns it: WITH CHECK refuses.
		assertThat(bob.post("/api/v1/notes", "{\"title\":\"planted\",\"owner_id\":\"" + aliceId + "\"}").status())
			.isEqualTo(403);
		// Alice tries to hand her note to Bob: the new row version fails WITH CHECK.
		assertThat(alice.patch("/api/v1/notes?id=eq." + aliceNoteId, "{\"owner_id\":\"" + bobId + "\"}").status())
			.isEqualTo(403);
		assertThat(anon.post("/api/v1/notes", "{\"title\":\"anonymous\"}").status()).isIn(403, 409);
		// Her own edits are fine.
		assertThat(alice.patch("/api/v1/notes?id=eq." + aliceNoteId, "{\"title\":\"alice's secret\"}").json().size())
			.isEqualTo(1);
	}

	@Test
	void anyoneAndNobodyRules() {
		assertThat(anon.get("/api/v1/posts").json().size()).isEqualTo(1); // select: anyone
		assertThat(bob.get("/api/v1/posts").json().size()).isEqualTo(1);
		assertThat(anon.post("/api/v1/posts", "{\"body\":\"spam\"}").status()).isEqualTo(403); // insert: owner
		assertThat(bob.patch("/api/v1/posts?body=eq.hello%20world", "{\"body\":\"edited\"}").body()).isEqualTo("[]"); // update: nobody
		assertThat(alice.delete("/api/v1/posts?body=eq.hello%20world").body()).isEqualTo("[]"); // delete: nobody, even for the author

		// A table with no rules is closed to everyone but the service key.
		assertThat(alice.get("/api/v1/locked").body()).isEqualTo("[]");
		assertThat(anon.get("/api/v1/locked").body()).isEqualTo("[]");
		assertThat(alice.post("/api/v1/locked", "{\"secret\":\"x\"}").status()).isEqualTo(403);
		assertThat(service.get("/api/v1/locked").json().size()).isEqualTo(1);
	}

	@Test
	void expansionObeysTheRulesOfTheExpandedTable() {
		// posts are public, but the users table lets each user read only their own row.
		JsonNode asAlice = alice.get("/api/v1/posts?select=body,users(email)").json().get(0);
		assertThat(asAlice.get("users").get("email").asString()).contains("alice");
		JsonNode asBob = bob.get("/api/v1/posts?select=body,users(email)").json().get(0);
		assertThat(asBob.get("users").isNull()).isTrue();
		// and in the other direction: Bob expanding Alice's notes through users sees none
		assertThat(bob.get("/api/v1/users?select=email,notes(title)").json().get(0).get("notes").size()).isZero();
		assertThat(bob.get("/api/v1/users").json().size()).isEqualTo(1);
	}

	// ---- tokens ---------------------------------------------------------

	@Test
	void forgedAndForeignTokensAreRejected() {
		String[] parts = aliceToken.split("\\.");
		// 1. Bob's token, with the payload swapped for Alice's: signature no longer matches.
		String bobSignature = signup(anon, email("mallory")).get("access_token").asString().split("\\.")[2];
		assertThat(anon.withBearer(parts[0] + "." + parts[1] + "." + bobSignature).get("/api/v1/notes").status())
			.isEqualTo(401);
		// 2. "alg: none" with no signature.
		String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(anon.withBearer(none + "." + parts[1] + ".").get("/api/v1/notes").status()).isEqualTo(401);
		// 3. Garbage and truncated tokens.
		assertThat(anon.withBearer("garbage").get("/api/v1/notes").status()).isEqualTo(401);
		assertThat(anon.withBearer(aliceToken.substring(0, aliceToken.length() - 2)).get("/api/v1/notes").status())
			.isEqualTo(401);

		// 4. A genuine token from ANOTHER project, used with this project's key and vice versa.
		TestClient otherDashboard = new TestClient(port);
		otherDashboard.signup(email("other-owner"));
		JsonNode other = otherDashboard.post("/platform/v1/projects", "{\"name\":\"other\"}").json();
		TestClient otherAnon = new TestClient(port).withApiKey(other.get("anonKey").asString());
		String foreignToken = signup(otherAnon, email("eve")).get("access_token").asString();
		assertThat(anon.withBearer(foreignToken).get("/api/v1/notes").status()).isEqualTo(401);
		assertThat(otherAnon.withBearer(aliceToken).get("/auth/v1/me").status()).isEqualTo(401);

		// 5. The service key does not need or use a user token.
		assertThat(service.withBearer("garbage").get("/api/v1/notes").status()).isEqualTo(200);
		// The real token still works after all that.
		assertThat(alice.get("/api/v1/notes").json().size()).isEqualTo(1);
	}

	// ---- the database holds even if our code is wrong -------------------

	/**
	 * Pretend the query compiler has a bug and forgets every filter. We run
	 * hand-written SQL with no WHERE clause as Bob, through the same
	 * TenantTransaction a request uses. Row-level security still applies.
	 */
	@Test
	void aBrokenApiLayerStillCannotLeakOrDamageOtherUsersRows() {
		TenantIdentity asBob = TenantIdentity.user(project, UUID.fromString(bobId));
		TenantIdentity asAnon = TenantIdentity.anon(project);

		Integer visibleToBob = tenant.execute(asBob, jdbc -> jdbc.queryForObject("select count(*) from notes", Integer.class));
		assertThat(visibleToBob).isZero();
		Integer updated = tenant.execute(asBob, jdbc -> jdbc.update("update notes set title = 'hacked'"));
		assertThat(updated).isZero();
		Integer deleted = tenant.execute(asBob, jdbc -> jdbc.update("delete from notes"));
		assertThat(deleted).isZero();
		Integer visibleToAnon = tenant.execute(asAnon, jdbc -> jdbc.queryForObject("select count(*) from notes", Integer.class));
		assertThat(visibleToAnon).isZero();
		Integer lockedForBob = tenant.execute(asBob, jdbc -> jdbc.queryForObject("select count(*) from locked", Integer.class));
		assertThat(lockedForBob).isZero();

		// Credentials and other platform tables are out of reach of every tenant role, the service role included.
		for (TenantIdentity identity : new TenantIdentity[] { asBob, asAnon, TenantIdentity.service(project) }) {
			for (String table : new String[] { "tsk_platform.end_user", "tsk_platform.end_user_session",
					"tsk_platform.api_key", "tsk_platform.project", "tsk_platform.session" }) {
				assertThatThrownBy(() -> tenant.execute(identity, jdbc -> jdbc.queryForList("select * from " + table)))
					.as(identity.role() + " reading " + table)
					.isInstanceOf(DataAccessException.class)
					.rootCause()
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			}
		}
		// A tenant role cannot switch off row-level security.
		assertThatThrownBy(() -> tenant.execute(asBob, jdbc -> {
			jdbc.execute("alter table notes disable row level security");
			return null;
		})).isInstanceOf(DataAccessException.class);
		assertThat(ownerJdbc.queryForObject("select title from \"" + project.schemaName() + "\".\"notes\" where id = ?::uuid",
				String.class, aliceNoteId))
			.isEqualTo("alice's secret");
	}

	/**
	 * The honest limit of the guarantee above (see DECISIONS.md D27).
	 *
	 * Row-level security protects against WRONG QUERIES: a missing filter, the
	 * wrong table, a forgotten check. It does not protect against ARBITRARY SQL
	 * running on the connection, because the login role is allowed to switch
	 * into any project role, and a second "SET ROLE" is just another statement.
	 *
	 * So "no arbitrary SQL" has to be guaranteed elsewhere: by the query
	 * compiler (InjectionTest, QueryApiTest). This test pins the limit down so
	 * that nobody reading the code believes the database alone covers it.
	 */
	@Test
	void knownLimitArbitrarySqlCouldSwitchRole() {
		TenantIdentity asBob = TenantIdentity.user(project, UUID.fromString(bobId));
		Integer seenAfterSwitch = tenant.execute(asBob, jdbc -> {
			jdbc.execute("set local role \"" + project.schemaName() + "_service\"");
			return jdbc.queryForObject("select count(*) from notes", Integer.class);
		});
		assertThat(seenAfterSwitch).isPositive();
		// Even then the platform's tables, and every password hash, stay unreadable.
		assertThatThrownBy(() -> tenant.execute(asBob, jdbc -> {
			jdbc.execute("reset role");
			return jdbc.queryForList("select * from tsk_platform.end_user");
		})).isInstanceOf(DataAccessException.class);
	}

	// ---- users table, rule changes, CORS --------------------------------

	@Test
	void usersTableIsWrittenOnlyBySignup() {
		assertThat(service.post("/api/v1/users", "{\"email\":\"injected@example.com\"}").status()).isEqualTo(400);
		assertThat(service.delete("/api/v1/users?id=eq." + bobId).status()).isEqualTo(403);
		assertThat(alice.delete("/api/v1/users?id=eq." + aliceId).status()).isEqualTo(403);
		String base = projectBase + "/migrations";
		assertThat(dashboard.post(base, "{\"operations\":[{\"op\":\"drop_table\",\"table\":\"users\"}]}").status())
			.isEqualTo(400);
		assertThat(dashboard.post(base,
				"{\"operations\":[{\"op\":\"set_rule\",\"table\":\"users\",\"operation\":\"insert\",\"rule\":\"anyone\"}]}")
			.status()).isEqualTo(400);
	}

	@Test
	void changingARuleTakesEffectAndRulesGuardTheirColumn() {
		String base = projectBase + "/migrations";
		String closed = "{\"operations\":[{\"op\":\"create_table\",\"table\":\"drafts\",\"columns\":[{\"name\":\"text\",\"type\":\"text\"},"
				+ "{\"name\":\"owner_id\",\"type\":\"uuid\",\"references\":\"users\"}]},"
				+ "{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"authenticated\"},"
				+ "{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"insert\",\"rule\":\"owner\",\"ownerColumn\":\"owner_id\"}]}";
		assertThat(dashboard.post(base, closed).status()).isEqualTo(200);
		assertThat(alice.post("/api/v1/drafts", "{\"text\":\"wip\"}").status()).isEqualTo(201);
		assertThat(bob.get("/api/v1/drafts").json().size()).isEqualTo(1); // authenticated: any logged-in user
		assertThat(anon.get("/api/v1/drafts").body()).isEqualTo("[]");

		assertThat(dashboard.post(base,
				"{\"operations\":[{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"nobody\"}]}")
			.status()).isEqualTo(200);
		assertThat(bob.get("/api/v1/drafts").body()).isEqualTo("[]");

		// the column a rule depends on cannot be dropped from under it
		assertThat(dashboard.post(base, "{\"operations\":[{\"op\":\"drop_column\",\"table\":\"drafts\",\"column\":\"owner_id\"}]}")
			.status()).isEqualTo(409);
		// invalid rules
		for (String bad : new String[] {
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"owner\"}",
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"owner\",\"ownerColumn\":\"text\"}",
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"owner\",\"ownerColumn\":\"id = id or true\"}",
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"anyone\",\"ownerColumn\":\"owner_id\"}",
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"truncate\",\"rule\":\"anyone\"}",
				"{\"op\":\"set_rule\",\"table\":\"drafts\",\"operation\":\"select\",\"rule\":\"true) or (true\"}" }) {
			assertThat(dashboard.post(base, "{\"operations\":[" + bad + "]}").status()).as(bad).isEqualTo(400);
		}
		JsonNode schema = dashboard.get(projectBase + "/schema").json();
		JsonNode drafts = schema.get("tables").valueStream().filter(t -> t.get("name").asString().equals("drafts")).findFirst().orElseThrow();
		assertThat(drafts.get("rules").get("insert").get("kind").asString()).isEqualTo("owner");
		assertThat(drafts.get("rules").has("select")).isFalse();
	}

	@Test
	void publicApiAllowsOtherOriginsButTheDashboardApiDoesNot() {
		TestClient browser = anon.withOrigin("https://some-app.example");
		TestClient.Response preflight = browser.preflight("/api/v1/posts", "PATCH");
		assertThat(preflight.status()).isEqualTo(200);
		assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("*");
		assertThat(preflight.header("Access-Control-Allow-Headers")).containsIgnoringCase("apikey");
		assertThat(browser.get("/api/v1/posts").header("Access-Control-Allow-Origin")).isEqualTo("*");
		assertThat(browser.preflight("/auth/v1/login", "POST").header("Access-Control-Allow-Origin")).isEqualTo("*");

		assertThat(browser.preflight("/platform/v1/projects", "POST").header("Access-Control-Allow-Origin")).isNull();
		assertThat(browser.get("/platform/v1/me").header("Access-Control-Allow-Origin")).isNull();
	}

}
