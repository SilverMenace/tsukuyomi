package dev.tsukuyomi;

import java.sql.SQLException;
import java.util.UUID;

import dev.tsukuyomi.support.TestClient;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantTransaction;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M1 slice, end to end, against a real PostgreSQL:
 * sign up, create a project, design a table, use the REST API, and check that
 * projects cannot reach each other.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndSliceTest {

	private static final String NOTES_TABLE = """
			{"operations":[{"op":"create_table","table":"notes","columns":[
			  {"name":"title","type":"text","nullable":false},
			  {"name":"done","type":"boolean"},
			  {"name":"meta","type":"jsonb"}]}]}""";

	@LocalServerPort
	int port;

	@Autowired
	TenantTransaction tenant;

	@Autowired
	JdbcTemplate ownerJdbc;

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	/** Signs up, creates a project with a notes table, and returns the create-project response. */
	private JsonNode newProjectWithNotes(TestClient dashboard, String name) {
		assertThat(dashboard.signup(uniqueEmail()).status()).isEqualTo(201);
		TestClient.Response created = dashboard.post("/platform/v1/projects", "{\"name\":\"" + name + "\"}");
		assertThat(created.status()).isEqualTo(201);
		JsonNode project = created.json();
		String id = project.get("project").get("id").asString();
		assertThat(dashboard.post("/platform/v1/projects/" + id + "/migrations", NOTES_TABLE).status()).isEqualTo(200);
		return project;
	}

	@Test
	void dashboardRequiresLogin() {
		TestClient anonymous = new TestClient(port);
		assertThat(anonymous.get("/platform/v1/me").status()).isEqualTo(401);
		assertThat(anonymous.get("/platform/v1/projects").status()).isEqualTo(401);
		assertThat(anonymous.get("/some/unknown/path").status()).isIn(401, 403);
	}

	@Test
	void signupLoginLogout() {
		TestClient dashboard = new TestClient(port);
		String email = uniqueEmail();
		TestClient.Response signup = dashboard.signup(email);
		assertThat(signup.status()).isEqualTo(201);
		assertThat(signup.setCookie()).contains("HttpOnly").contains("SameSite=Strict");
		assertThat(dashboard.get("/platform/v1/me").json().get("email").asString()).isEqualTo(email);

		assertThat(dashboard.signup(email).status()).isEqualTo(409); // same email again
		assertThat(dashboard.delete("/platform/v1/session").status()).isEqualTo(204);
		assertThat(dashboard.get("/platform/v1/me").status()).isEqualTo(401);

		String wrong = "{\"email\":\"" + email + "\",\"password\":\"not the password\"}";
		assertThat(dashboard.post("/platform/v1/session", wrong).status()).isEqualTo(401);
		String right = "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}";
		assertThat(dashboard.post("/platform/v1/session", right).status()).isEqualTo(204);
		assertThat(dashboard.get("/platform/v1/me").status()).isEqualTo(200);
	}

	@Test
	void designTableThenUseRestApi() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "slice");
		String id = project.get("project").get("id").asString();
		TestClient service = new TestClient(port).withApiKey(project.get("serviceKey").asString());

		// insert through the public API
		TestClient.Response inserted = service.post("/api/v1/notes",
				"{\"title\":\"first\",\"done\":true,\"meta\":{\"tags\":[\"a\",\"b\"]}}");
		assertThat(inserted.status()).isEqualTo(201);
		assertThat(inserted.json().get("id").asString()).hasSize(36);
		assertThat(inserted.json().get("meta").get("tags").get(1).asString()).isEqualTo("b");
		assertThat(service.post("/api/v1/notes", "{\"title\":\"second\"}").status()).isEqualTo(201);

		// list through the public API
		JsonNode rows = service.get("/api/v1/notes").json();
		assertThat(rows.size()).isEqualTo(2);
		assertThat(rows.get(0).get("title").asString()).isEqualTo("first");
		assertThat(service.get("/api/v1/notes?limit=1&offset=1").json().get(0).get("title").asString())
			.isEqualTo("second");

		// the dashboard's data browser sees the same rows
		assertThat(dashboard.get("/platform/v1/projects/" + id + "/tables/notes/rows").json().size()).isEqualTo(2);

		// schema and history are visible to the owner
		JsonNode schema = dashboard.get("/platform/v1/projects/" + id + "/schema").json();
		assertThat(schema.get("version").asInt()).isEqualTo(1);
		assertThat(schema.get("tables").get(0).get("columns").size()).isEqualTo(5);
		JsonNode history = dashboard.get("/platform/v1/projects/" + id + "/migrations").json();
		assertThat(history.get(0).get("sql").asString()).contains("create table").contains("force row level security");
	}

	@Test
	void badRequestsGetClearErrors() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "errors");
		TestClient service = new TestClient(port).withApiKey(project.get("serviceKey").asString());

		assertThat(service.get("/api/v1/nope").status()).isEqualTo(404);
		assertThat(service.post("/api/v1/notes", "{\"nope\":1}").status()).isEqualTo(400);
		assertThat(service.post("/api/v1/notes", "{\"id\":\"" + UUID.randomUUID() + "\",\"title\":\"x\"}").status())
			.isEqualTo(400); // system columns are not writable
		assertThat(service.post("/api/v1/notes", "{\"done\":true}").status()).isEqualTo(409); // title is NOT NULL
		assertThat(service.post("/api/v1/notes", "{\"title\":\"x\",\"done\":\"maybe\"}").status()).isEqualTo(400);
		assertThat(service.get("/api/v1/notes?limit=1000").status()).isEqualTo(400);
		// a value that looks like SQL is just a value
		String nasty = "'); drop table notes; --";
		assertThat(service.post("/api/v1/notes", "{\"title\":\"" + nasty + "\"}").status()).isEqualTo(201);
		assertThat(service.get("/api/v1/notes").json().get(0).get("title").asString()).isEqualTo(nasty);
	}

	@Test
	void migrationsValidateAndDryRunChangesNothing() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "migrations");
		String base = "/platform/v1/projects/" + project.get("project").get("id").asString();

		String addColumn = "{\"operations\":[{\"op\":\"add_column\",\"table\":\"notes\",\"column\":{\"name\":\"body\",\"type\":\"text\"}}]}";
		TestClient.Response dryRun = dashboard.post(base + "/migrations?dryRun=true", addColumn);
		assertThat(dryRun.status()).isEqualTo(200);
		assertThat(dryRun.json().get("applied").asBoolean()).isFalse();
		assertThat(dryRun.json().get("sql").asString()).contains("add column \"body\" text");
		assertThat(dashboard.get(base + "/schema").json().get("version").asInt()).isEqualTo(1);

		assertThat(dashboard.post(base + "/migrations", addColumn).json().get("version").asInt()).isEqualTo(2);
		assertThat(dashboard.get(base + "/schema").json().get("tables").get(0).get("columns").size()).isEqualTo(6);

		String injection = "{\"operations\":[{\"op\":\"create_table\",\"table\":\"x\\\"; drop schema tsk_platform cascade; --\",\"columns\":[]}]}";
		assertThat(dashboard.post(base + "/migrations", injection).status()).isEqualTo(400);
		String badType = "{\"operations\":[{\"op\":\"add_column\",\"table\":\"notes\",\"column\":{\"name\":\"z\",\"type\":\"text; drop table notes\"}}]}";
		assertThat(dashboard.post(base + "/migrations", badType).status()).isEqualTo(400);
		// a failing statement rolls the whole migration back: NOT NULL column on a table with rows
		TestClient service = new TestClient(port).withApiKey(project.get("serviceKey").asString());
		service.post("/api/v1/notes", "{\"title\":\"row\"}");
		String twoSteps = "{\"operations\":[{\"op\":\"create_table\",\"table\":\"other\",\"columns\":[]},"
				+ "{\"op\":\"add_column\",\"table\":\"notes\",\"column\":{\"name\":\"must\",\"type\":\"text\",\"nullable\":false}}]}";
		assertThat(dashboard.post(base + "/migrations", twoSteps).status()).isEqualTo(400);
		assertThat(dashboard.get(base + "/schema").json().get("tables").size()).isEqualTo(1); // "other" was not created
	}

	@Test
	void anonKeyIsDeniedUntilRulesAllowIt() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "anon");
		new TestClient(port).withApiKey(project.get("serviceKey").asString()).post("/api/v1/notes", "{\"title\":\"secret\"}");
		TestClient anon = new TestClient(port).withApiKey(project.get("anonKey").asString());

		// Row-level security with no policy for this role: reads see nothing, writes are refused.
		assertThat(anon.get("/api/v1/notes").body()).isEqualTo("[]");
		assertThat(anon.post("/api/v1/notes", "{\"title\":\"x\"}").status()).isEqualTo(403);

		assertThat(new TestClient(port).get("/api/v1/notes").status()).isEqualTo(401);
		assertThat(new TestClient(port).withApiKey("tsk_service_" + "0".repeat(48)).get("/api/v1/notes").status())
			.isEqualTo(401);
	}

	@Test
	void projectsAreIsolatedFromEachOther() {
		TestClient alice = new TestClient(port);
		JsonNode a = newProjectWithNotes(alice, "alice");
		TestClient bob = new TestClient(port);
		JsonNode b = newProjectWithNotes(bob, "bob");
		String aId = a.get("project").get("id").asString();
		new TestClient(port).withApiKey(a.get("serviceKey").asString()).post("/api/v1/notes", "{\"title\":\"alice's\"}");

		// API level: Bob's dashboard session cannot touch Alice's project ...
		assertThat(bob.get("/platform/v1/projects/" + aId).status()).isEqualTo(404);
		assertThat(bob.get("/platform/v1/projects/" + aId + "/tables/notes/rows").status()).isEqualTo(404);
		assertThat(bob.post("/platform/v1/projects/" + aId + "/migrations", NOTES_TABLE).status()).isEqualTo(404);
		assertThat(bob.delete("/platform/v1/projects/" + aId).status()).isEqualTo(404);
		// ... and Bob's service key only ever sees Bob's (empty) table of the same name.
		assertThat(new TestClient(port).withApiKey(b.get("serviceKey").asString()).get("/api/v1/notes").body())
			.isEqualTo("[]");

		// Database level: pretend the API layer has a bug and runs SQL that names
		// Alice's schema while acting as Bob's most powerful role. Postgres refuses.
		ProjectRef aliceRef = new ProjectRef(UUID.fromString(aId), a.get("project").get("schemaName").asString());
		ProjectRef bobRef = new ProjectRef(UUID.fromString(b.get("project").get("id").asString()),
				b.get("project").get("schemaName").asString());
		String stolen = "select count(*) from \"" + aliceRef.schemaName() + "\".\"notes\"";
		assertThatThrownBy(() -> tenant.execute(TenantIdentity.service(bobRef),
				jdbc -> jdbc.queryForObject(stolen, Integer.class)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		// The platform's own tables are just as unreachable from a tenant role.
		assertThatThrownBy(() -> tenant.execute(TenantIdentity.service(bobRef),
				jdbc -> jdbc.queryForObject("select count(*) from tsk_platform.account", Integer.class)))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		// Sanity check that the same call works for the rightful owner.
		Integer ownCount = tenant.execute(TenantIdentity.service(aliceRef),
				jdbc -> jdbc.queryForObject(stolen, Integer.class));
		assertThat(ownCount).isEqualTo(1);
	}

	@Test
	void identityDoesNotLeakBetweenPooledConnections() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "pooling");
		ProjectRef ref = new ProjectRef(UUID.fromString(project.get("project").get("id").asString()),
				project.get("project").get("schemaName").asString());
		UUID user = UUID.randomUUID();

		// Inside the transaction the role and user id are set ...
		String inside = tenant.execute(TenantIdentity.user(ref, user),
				jdbc -> jdbc.queryForObject("select current_user || '|' || current_setting('tsk.user_id')", String.class));
		assertThat(inside).isEqualTo(ref.schemaName() + "_user|" + user);

		// ... and the next transactions on the (small, reused) pool start clean.
		for (int i = 0; i < 20; i++) {
			String next = tenant.execute(TenantIdentity.anon(ref),
					jdbc -> jdbc.queryForObject("select current_user || '|' || current_setting('tsk.user_id')", String.class));
			assertThat(next).isEqualTo(ref.schemaName() + "_anon|");
		}
	}

	@Test
	void deletingAProjectRemovesItsSchemaAndRoles() {
		TestClient dashboard = new TestClient(port);
		JsonNode project = newProjectWithNotes(dashboard, "doomed");
		String schema = project.get("project").get("schemaName").asString();
		assertThat(dashboard.delete("/platform/v1/projects/" + project.get("project").get("id").asString()).status())
			.isEqualTo(204);

		assertThat(ownerJdbc.queryForObject("select count(*) from pg_namespace where nspname = ?", Integer.class, schema))
			.isZero();
		assertThat(ownerJdbc.queryForObject("select count(*) from pg_roles where rolname like ?", Integer.class,
				schema + "\\_%"))
			.isZero();
		assertThat(new TestClient(port).withApiKey(project.get("serviceKey").asString()).get("/api/v1/notes").status())
			.isEqualTo(401);
	}

}
