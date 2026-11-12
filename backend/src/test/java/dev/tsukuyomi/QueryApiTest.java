package dev.tsukuyomi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.support.TestClient;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query compiler over real HTTP against a real PostgreSQL: filtering,
 * sorting, pagination, expansion, writes, and injection attempts that are
 * checked by looking at what actually happened in the database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryApiTest {

	private static final String SCHEMA = """
			{"operations":[
			 {"op":"create_table","table":"authors","columns":[{"name":"name","type":"text","nullable":false}]},
			 {"op":"create_table","table":"posts","columns":[
			   {"name":"title","type":"text","nullable":false},
			   {"name":"priority","type":"integer"},
			   {"name":"published","type":"boolean"},
			   {"name":"meta","type":"jsonb"},
			   {"name":"author_id","type":"uuid","references":"authors","onDelete":"cascade"}]},
			 {"op":"create_table","table":"comments","columns":[
			   {"name":"body","type":"text"},
			   {"name":"post_id","type":"uuid","references":"posts","onDelete":"cascade"}]},
			 {"op":"create_table","table":"canary","columns":[{"name":"note","type":"text"}]}
			]}""";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate ownerJdbc;

	private TestClient api;

	private String schemaName;

	private String adaId;

	private String firstPostId;

	@BeforeAll
	void createProjectWithData() {
		TestClient dashboard = new TestClient(port);
		assertThat(dashboard.signup("query-" + UUID.randomUUID() + "@example.com").status()).isEqualTo(201);
		JsonNode project = dashboard.post("/platform/v1/projects", "{\"name\":\"query\"}").json();
		String id = project.get("project").get("id").asString();
		schemaName = project.get("project").get("schemaName").asString();
		TestClient.Response migrated = dashboard.post("/platform/v1/projects/" + id + "/migrations", SCHEMA);
		assertThat(migrated.status()).as(migrated.body()).isEqualTo(200);
		assertThat(migrated.json().get("sql").asString()).contains("references").contains("create index \"posts_author_id_idx\"");
		api = new TestClient(port).withApiKey(project.get("serviceKey").asString());

		JsonNode authors = api.post("/api/v1/authors", "[{\"name\":\"Ada\"},{\"name\":\"Grace\"}]").json();
		adaId = authors.get(0).get("id").asString();
		String graceId = authors.get(1).get("id").asString();
		JsonNode posts = api.post("/api/v1/posts", """
				[{"title":"Alpha report","priority":1,"published":true,"meta":{"tags":["x"]},"author_id":"%s"},
				 {"title":"Beta notes","priority":2,"published":false,"meta":null,"author_id":"%s"},
				 {"title":"Gamma report","priority":3,"published":true,"meta":null,"author_id":"%s"},
				 {"title":"Delta draft","priority":null,"published":null,"meta":null,"author_id":null}]"""
			.formatted(adaId, adaId, graceId)).json();
		assertThat(posts.size()).isEqualTo(4);
		firstPostId = posts.get(0).get("id").asString();
		api.post("/api/v1/comments", "[{\"body\":\"first!\",\"post_id\":\"%s\"},{\"body\":\"second\",\"post_id\":\"%s\"}]"
			.formatted(firstPostId, firstPostId));
		api.post("/api/v1/canary", "{\"note\":\"still here\"}");
	}

	private List<String> titles(String query) {
		TestClient.Response response = api.get("/api/v1/posts?" + query);
		assertThat(response.status()).as(response.body()).isEqualTo(200);
		return response.json().valueStream().map(row -> row.get("title").asString()).toList();
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	@Test
	void filters() {
		assertThat(titles("priority=gte.2&order=priority")).containsExactly("Beta notes", "Gamma report");
		assertThat(titles("priority=lt.3&priority=gt.1")).containsExactly("Beta notes");
		assertThat(titles("title=ilike.*REPORT*&order=title")).containsExactly("Alpha report", "Gamma report");
		assertThat(titles("title=like.*REPORT*")).isEmpty(); // like is case-sensitive
		assertThat(titles("published=is.true&order=title")).containsExactly("Alpha report", "Gamma report");
		assertThat(titles("published=is.null")).containsExactly("Delta draft");
		assertThat(titles("priority=not.is.null&order=title.desc")).containsExactly("Gamma report", "Beta notes", "Alpha report");
		assertThat(titles("priority=in.(1,3)&order=title")).containsExactly("Alpha report", "Gamma report");
		assertThat(titles("priority=not.in.(1,3)")).containsExactly("Beta notes");
		assertThat(titles("title=neq.Beta%20notes&author_id=eq." + adaId)).containsExactly("Alpha report");
		assertThat(titles("title=eq." + encode("Alpha report"))).containsExactly("Alpha report");
	}

	@Test
	void sortingAndPagination() {
		assertThat(titles("order=priority.desc.nullslast,title")).containsExactly("Gamma report", "Beta notes",
				"Alpha report", "Delta draft");
		assertThat(titles("order=priority.asc.nullsfirst")).first().isEqualTo("Delta draft");
		assertThat(titles("order=title&limit=2")).containsExactly("Alpha report", "Beta notes");
		assertThat(titles("order=title&limit=2&offset=2")).containsExactly("Delta draft", "Gamma report");
		assertThat(titles("order=title&limit=2&offset=4")).isEmpty();
	}

	@Test
	void selectChoosesColumns() {
		JsonNode row = api.get("/api/v1/posts?select=title,priority&title=eq." + encode("Alpha report")).json().get(0);
		assertThat(row.propertyNames()).containsExactly("title", "priority");
		assertThat(row.get("priority").asInt()).isEqualTo(1);
	}

	@Test
	void expansionToOneAndToMany() {
		JsonNode post = api.get("/api/v1/posts?select=title,authors(name),comments(body)&id=eq." + firstPostId)
			.json()
			.get(0);
		assertThat(post.get("authors").get("name").asString()).isEqualTo("Ada");
		assertThat(post.get("comments").valueStream().map(c -> c.get("body").asString())).containsExactly("first!", "second");

		// no author: null. no comments: empty list.
		JsonNode draft = api.get("/api/v1/posts?select=title,authors(name),comments(body)&title=eq." + encode("Delta draft"))
			.json()
			.get(0);
		assertThat(draft.get("authors").isNull()).isTrue();
		assertThat(draft.get("comments").size()).isZero();

		// two levels: author -> posts -> comments
		JsonNode ada = api.get("/api/v1/authors?select=name,posts(title,comments(body))&name=eq.Ada").json().get(0);
		assertThat(ada.get("posts").size()).isEqualTo(2);
		assertThat(ada.get("posts").get(0).get("comments").size()).isEqualTo(2);
	}

	@Test
	void totalCountOnRequest() {
		// TestClient cannot send custom headers, so use the JDK client directly for this one.
		try {
			var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
					"http://localhost:" + port + "/api/v1/posts?priority=not.is.null&limit=1"))
				.header("apikey", api.apiKey())
				.header("Prefer", "count=exact")
				.build();
			var response = java.net.http.HttpClient.newHttpClient()
				.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
			assertThat(response.headers().firstValue("X-Total-Count")).contains("3");
			assertThat(new tools.jackson.databind.ObjectMapper().readTree(response.body()).size()).isEqualTo(1);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
		assertThat(api.get("/api/v1/posts").status()).isEqualTo(200);
	}

	@Test
	void updateAndDelete() {
		String id = api.post("/api/v1/posts", "{\"title\":\"temp\",\"priority\":50}").json().get("id").asString();

		JsonNode changed = api.patch("/api/v1/posts?id=eq." + id, "{\"title\":\"renamed\",\"priority\":null}").json();
		assertThat(changed.size()).isEqualTo(1);
		assertThat(changed.get(0).get("title").asString()).isEqualTo("renamed");
		assertThat(changed.get(0).get("priority").isNull()).isTrue();

		assertThat(api.patch("/api/v1/posts", "{\"title\":\"everything\"}").status()).isEqualTo(400); // no filter
		assertThat(api.delete("/api/v1/posts").status()).isEqualTo(400); // no filter
		assertThat(api.patch("/api/v1/posts?id=eq." + id, "{\"id\":\"" + UUID.randomUUID() + "\"}").status()).isEqualTo(400);

		JsonNode deleted = api.delete("/api/v1/posts?id=eq." + id).json();
		assertThat(deleted.get(0).get("title").asString()).isEqualTo("renamed");
		assertThat(titles("title=eq.renamed")).isEmpty();
	}

	@Test
	void foreignKeysAreEnforcedAndCascade() {
		assertThat(api.post("/api/v1/posts", "{\"title\":\"orphan\",\"author_id\":\"" + UUID.randomUUID() + "\"}").status())
			.isEqualTo(409);

		String author = api.post("/api/v1/authors", "{\"name\":\"Temp\"}").json().get("id").asString();
		api.post("/api/v1/posts", "{\"title\":\"by temp\",\"author_id\":\"" + author + "\"}");
		assertThat(api.delete("/api/v1/authors?id=eq." + author).status()).isEqualTo(200);
		assertThat(titles("title=eq." + encode("by temp"))).isEmpty(); // on delete cascade
	}

	@Test
	void badQueriesAre400NotErrors() {
		for (String query : List.of("select=nope", "nope=eq.1", "order=nope", "priority=eq.abc", "priority=like.1",
				"limit=0", "limit=1000", "offset=-1", "select=comments(nope)", "select=canary(note)", "title=is.true",
				"priority=in.(1,x)", "id=eq.not-a-uuid", "select=a(b(c(d)))", "title=bogus.1", "limit=1&limit=2")) {
			TestClient.Response response = api.get("/api/v1/posts?" + query);
			assertThat(response.status()).as(query + " -> " + response.body()).isEqualTo(400);
			assertThat(response.json().get("error").get("code").asString()).isIn("invalid_query", "invalid_value");
		}
	}

	/**
	 * Injection attempts over real HTTP. Unlike the unit tests, this does not
	 * inspect the SQL: it checks the outcome. No request may cause a 5xx, and
	 * afterwards the database must be exactly as it was.
	 */
	@Test
	void injectionAttemptsChangeNothing() {
		List<String> payloads = List.of("'", "\"", ";", "--", "' or '1'='1", "1; drop table canary; --",
				"x'; delete from canary; --", "\"; drop table canary; --", "1) or (1=1", "*/ drop table canary /*",
				"title\" from canary --", "$1", "?", "(select note from canary)", "canary", "pg_sleep(5)",
				"x' union select note from canary --", "\\", "%27%3B", "\u0000", "eq.1;drop table canary");
		int requests = 0;
		for (String payload : payloads) {
			String p = encode(payload);
			for (String path : List.of("/api/v1/posts?select=" + p, "/api/v1/posts?select=id," + p,
					"/api/v1/posts?select=comments(" + p + ")", "/api/v1/posts?select=" + p + "(id)",
					"/api/v1/posts?order=" + p, "/api/v1/posts?order=title." + p, "/api/v1/posts?limit=" + p,
					"/api/v1/posts?offset=" + p, "/api/v1/posts?" + p + "=eq.1", "/api/v1/posts?title=" + p,
					"/api/v1/posts?title=eq." + p, "/api/v1/posts?title=like." + p, "/api/v1/posts?priority=eq." + p,
					"/api/v1/posts?priority=in.(" + p + ")", "/api/v1/posts?id=eq." + p, "/api/v1/" + p,
					"/api/v1/posts?title=not." + p + ".x")) {
				TestClient.Response response = api.get(path);
				assertThat(response.status()).as("GET " + path + " -> " + response.body()).isLessThan(500);
				requests++;
			}
			String quoted = new tools.jackson.databind.ObjectMapper().writeValueAsString(payload);
			assertThat(api.post("/api/v1/posts", "{" + quoted + ":\"x\"}").status()).isEqualTo(400); // as a field name
			assertThat(api.patch("/api/v1/posts?title=eq." + p, "{\"title\":" + quoted + "}").status()).isLessThan(500);
			assertThat(api.delete("/api/v1/posts?" + p + "=eq.1").status()).isIn(400, 404);
			if (payload.indexOf('\u0000') < 0) { // Postgres text cannot hold a zero byte; that one is a 400
				// As a value it is stored and read back byte for byte.
				TestClient.Response stored = api.post("/api/v1/canary", "{\"note\":" + quoted + "}");
				assertThat(stored.status()).as(stored.body()).isEqualTo(201);
				assertThat(stored.json().get("note").asString()).isEqualTo(payload);
				assertThat(api.delete("/api/v1/canary?id=eq." + stored.json().get("id").asString()).status()).isEqualTo(200);
			}
		}
		assertThat(requests).isGreaterThan(300);

		// The database is exactly as before.
		assertThat(ownerJdbc.queryForObject("select note from \"" + schemaName + "\".\"canary\"", String.class))
			.isEqualTo("still here");
		assertThat(ownerJdbc.queryForObject(
				"select count(*) from information_schema.tables where table_schema = ?", Integer.class, schemaName))
			.isEqualTo(4);
		assertThat(titles("order=title")).containsExactly("Alpha report", "Beta notes", "Delta draft", "Gamma report");
		assertThat(ownerJdbc.queryForObject("select count(*) from tsk_platform.account", Integer.class)).isPositive();
	}

}
