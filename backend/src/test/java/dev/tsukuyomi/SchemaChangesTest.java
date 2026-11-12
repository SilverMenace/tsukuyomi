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
 * Changing tables that already exist and hold rows, against a real Postgres:
 * renames must not break rules, relations or live updates, and a change the
 * data does not allow must leave everything exactly as it was.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaChangesTest {

	@LocalServerPort
	int port;

	private TestClient dashboard;

	private String migrations;

	private TestClient service;

	private TestClient alice;

	private TestClient bob;

	private String serviceKey;

	private TestClient.Response migrate(String operations) {
		return dashboard.post(migrations, "{\"operations\":[" + operations + "]}");
	}

	private void migrateOk(String operations) {
		TestClient.Response response = migrate(operations);
		assertThat(response.status()).as(response.body()).isEqualTo(200);
	}

	private TestClient user(TestClient anon, String name) {
		TestClient.Response response = anon.post("/auth/v1/signup",
				"{\"email\":\"" + name + "-" + UUID.randomUUID() + "@example.com\",\"password\":\"correct horse battery\"}");
		assertThat(response.status()).as(response.body()).isEqualTo(201);
		return anon.withBearer(response.json().get("access_token").asString());
	}

	@BeforeAll
	void setUp() {
		dashboard = new TestClient(port);
		dashboard.signup("owner-" + UUID.randomUUID() + "@example.com");
		JsonNode created = dashboard.post("/platform/v1/projects", "{\"name\":\"changes\"}").json();
		migrations = "/platform/v1/projects/" + created.get("project").get("id").asString() + "/migrations";
		serviceKey = created.get("serviceKey").asString();
		service = new TestClient(port).withApiKey(serviceKey);
		TestClient anon = new TestClient(port).withApiKey(created.get("anonKey").asString());
		alice = user(anon, "alice");
		bob = user(anon, "bob");
		migrateOk("""
				{"op":"create_table","table":"lists","columns":[
				  {"name":"title","type":"text","unique":true},
				  {"name":"owner_id","type":"uuid","nullable":false,"references":"users","onDelete":"cascade"}]},
				{"op":"set_rule","table":"lists","operation":"select","rule":"owner","ownerColumn":"owner_id"},
				{"op":"set_rule","table":"lists","operation":"insert","rule":"owner","ownerColumn":"owner_id"},
				{"op":"create_table","table":"items","columns":[
				  {"name":"label","type":"text"},
				  {"name":"list_id","type":"uuid","nullable":false,"references":"lists","onDelete":"cascade"}]}""");
		String listId = alice.post("/api/v1/lists", "{\"title\":\"groceries\"}").json().get("id").asString();
		service.post("/api/v1/items", "{\"label\":\"milk\",\"list_id\":\"" + listId + "\"}");
		service.post("/api/v1/items", "{\"label\":null,\"list_id\":\"" + listId + "\"}");
	}

	@Test
	void renamesKeepRowsRulesRelationsAndLiveUpdates() {
		migrateOk("""
				{"op":"rename_table","table":"lists","to":"boards"},
				{"op":"rename_column","table":"boards","column":"owner_id","to":"author"},
				{"op":"rename_column","table":"boards","column":"title","to":"name"},
				{"op":"rename_column","table":"items","column":"list_id","to":"board_id"}""");

		// The old names are gone, the rows are still there under the new ones.
		assertThat(service.get("/api/v1/lists").status()).isEqualTo(404);
		assertThat(service.get("/api/v1/boards?select=name,items(label)").body()).contains("groceries").contains("milk");
		// The owner rule followed the renamed column: Bob still sees nothing, and
		// a new row still gets its author filled in.
		assertThat(bob.get("/api/v1/boards").body()).isEqualTo("[]");
		JsonNode mine = bob.post("/api/v1/boards", "{\"name\":\"bob's\"}").json();
		assertThat(mine.get("author").isNull()).isFalse();
		assertThat(alice.get("/api/v1/boards").body()).doesNotContain("bob's");
		// The unique constraint followed too (it was renamed with the column).
		assertThat(alice.post("/api/v1/boards", "{\"name\":\"groceries\"}").status()).isEqualTo(409);

		// Constraints were renamed along, so later changes still find them by name.
		migrateOk("""
				{"op":"set_unique","table":"boards","column":"name","unique":false},
				{"op":"set_unique","table":"boards","column":"name","unique":true},
				{"op":"drop_column","table":"items","column":"board_id"},
				{"op":"add_column","table":"items","column":{"name":"board_id","type":"uuid","references":"boards"}}""");

		// Live updates use the new table name.
		try (LiveTestClient live = LiveTestClient.connect(port, serviceKey, null)) {
			assertThat(live.subscribe("lists", null).get("code").asString()).isEqualTo("unknown_table");
			assertThat(live.subscribe("boards", null).get("type").asString()).isEqualTo("subscribed");
			String id = alice.post("/api/v1/boards", "{\"name\":\"live\"}").json().get("id").asString();
			assertThat(live.next().get("id").asString()).isEqualTo(id);
		}
	}

	@Test
	void aChangeTheDataDoesNotAllowChangesNothing() {
		// One item has no label, so "required" must be refused by the database...
		TestClient.Response refused = migrate("""
				{"op":"add_column","table":"items","column":{"name":"done","type":"boolean"}},
				{"op":"set_nullable","table":"items","column":"label","nullable":false}""");
		assertThat(refused.status()).isEqualTo(400);
		assertThat(refused.json().get("error").get("code").asString()).isEqualTo("migration_failed");
		// ...and the first operation of the same migration is rolled back with it.
		assertThat(service.get("/api/v1/items?select=done").status()).isEqualTo(400);

		// After fixing the data the same change goes through and is enforced.
		service.patch("/api/v1/items?label=is.null", "{\"label\":\"eggs\"}");
		migrateOk("{\"op\":\"set_nullable\",\"table\":\"items\",\"column\":\"label\",\"nullable\":false}");
		assertThat(service.post("/api/v1/items", "{\"label\":null}").status()).isIn(400, 409);
		migrateOk("{\"op\":\"set_nullable\",\"table\":\"items\",\"column\":\"label\",\"nullable\":true}");

		// Two items with the same label: unique is refused, then allowed once they differ.
		service.patch("/api/v1/items?label=eq.eggs", "{\"label\":\"milk\"}");
		assertThat(migrate("{\"op\":\"set_unique\",\"table\":\"items\",\"column\":\"label\",\"unique\":true}").status())
			.isEqualTo(400);
	}

	@Test
	void whatMayNotBeChanged() {
		assertThat(migrate("{\"op\":\"rename_table\",\"table\":\"users\",\"to\":\"people\"}").json()
			.get("error").get("code").asString()).isEqualTo("builtin_table");
		assertThat(migrate("{\"op\":\"rename_column\",\"table\":\"users\",\"column\":\"email\",\"to\":\"mail\"}").json()
			.get("error").get("code").asString()).isEqualTo("system_column");
		assertThat(migrate("{\"op\":\"rename_column\",\"table\":\"items\",\"column\":\"id\",\"to\":\"key\"}").json()
			.get("error").get("code").asString()).isEqualTo("system_column");
		assertThat(migrate("{\"op\":\"rename_table\",\"table\":\"items\",\"to\":\"users\"}").status()).isEqualTo(400);
		assertThat(migrate("{\"op\":\"rename_table\",\"table\":\"items\",\"to\":\"x\\\"; drop table items; --\"}").status())
			.isEqualTo(400);
		assertThat(migrate("{\"op\":\"rename_column\",\"table\":\"items\",\"column\":\"label\",\"to\":\"Label\"}").status())
			.isEqualTo(400);
		assertThat(migrate("{\"op\":\"rename_column\",\"table\":\"items\",\"column\":\"label\",\"to\":\"created_at\"}")
			.status()).isIn(400, 409);
		assertThat(migrate("{\"op\":\"set_nullable\",\"table\":\"items\",\"column\":\"label\",\"nullable\":true}").json()
			.get("error").get("code").asString()).isEqualTo("no_change");
		assertThat(service.get("/api/v1/items").status()).isEqualTo(200);
	}

}
