package dev.tsukuyomi.query;

import java.util.List;
import java.util.Map;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.query.parse.QueryParser;

import org.junit.jupiter.api.Test;

import static dev.tsukuyomi.query.Fixtures.LIMITS;
import static dev.tsukuyomi.query.Fixtures.PROJECT;
import static dev.tsukuyomi.query.Fixtures.params;
import static dev.tsukuyomi.query.Fixtures.select;
import static dev.tsukuyomi.query.Fixtures.validator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What SQL comes out for a given request. No database needed. */
class SqlBuilderTest {

	private static final String POSTS = "\"p_0123456789ab\".\"posts\"";

	@Test
	void plainListSelectsAllColumnsWithStableOrderAndBoundPaging() {
		CompiledSql compiled = select("authors", params());

		assertThat(compiled.sql()).isEqualTo("select coalesce(json_agg(row_to_json(r)), '[]'::json)::text from ("
				+ "select t0.\"id\", t0.\"created_at\", t0.\"name\" from \"p_0123456789ab\".\"authors\" as t0"
				+ " order by t0.\"created_at\", t0.\"id\" limit ? offset ?) r");
		assertThat(compiled.parameters()).containsExactly(20, 0);
	}

	@Test
	void filtersBecomeCastBindParameters() {
		CompiledSql compiled = select("posts", params("select", "id,title", "priority", "gte.3", "title",
				"ilike.*report*", "published", "is.true", "author_id", "not.is.null", "priority", "in.(1,2,3)",
				"title", "not.eq.x", "limit", "5", "offset", "10"));

		assertThat(compiled.sql()).contains("select t0.\"id\", t0.\"title\" from " + POSTS + " as t0 where ")
			.contains("t0.\"priority\" >= cast(? as integer)")
			.contains("t0.\"title\" ilike ?")
			.contains("t0.\"published\" is true")
			.contains("not (t0.\"author_id\" is null)")
			.contains("t0.\"priority\" in (cast(? as integer), cast(? as integer), cast(? as integer))")
			.contains("not (t0.\"title\" = cast(? as text))");
		// values travel separately, in the order their placeholders appear
		assertThat(compiled.parameters()).containsExactly("3", "1", "2", "3", "%report%", "x", 5, 10);
	}

	@Test
	void orderTermsAreFollowedByIdAsTieBreaker() {
		assertThat(select("posts", params("order", "priority.desc.nullslast,title")).sql())
			.contains(" order by t0.\"priority\" desc nulls last, t0.\"title\", t0.\"id\" limit");
		assertThat(select("posts", params("order", "id.desc")).sql()).contains(" order by t0.\"id\" desc limit");
	}

	@Test
	void toOneExpansionIsACorrelatedSubSelect() {
		CompiledSql compiled = select("comments", params("select", "body,posts(title)"));

		assertThat(compiled.sql()).contains("select t0.\"body\", (select row_to_json(e1) from ("
				+ "select t1.\"title\" from " + POSTS + " as t1 where t1.\"id\" = t0.\"post_id\") e1) as \"posts\""
				+ " from \"p_0123456789ab\".\"comments\" as t0");
	}

	@Test
	void toManyExpansionIsAnOrderedCappedList() {
		CompiledSql compiled = select("posts", params("select", "title,comments(body)"));

		assertThat(compiled.sql()).contains("(select coalesce(json_agg(row_to_json(e1)), '[]'::json) from ("
				+ "select t1.\"body\" from \"p_0123456789ab\".\"comments\" as t1 where t1.\"post_id\" = t0.\"id\""
				+ " order by t1.\"created_at\", t1.\"id\" limit ?) e1) as \"comments\"");
		assertThat(compiled.parameters()).containsExactly(100, 20, 0);
	}

	@Test
	void expansionsNestTwoLevels() {
		String sql = select("authors", params("select", "name,posts!author_id(title,comments(body))", "limit", "1")).sql();
		assertThat(sql).contains("as t1 where t1.\"author_id\" = t0.\"id\"").contains("as t2 where t2.\"post_id\" = t1.\"id\"");
	}

	@Test
	void totalRowBudgetStopsMultiplyingExpansions() {
		// 20 authors x 100 posts x 100 comments = 200,000 nested rows: refused
		assertThatThrownBy(() -> select("authors", params("select", "name,posts!author_id(title,comments(body))")))
			.hasMessageContaining("could return up to 202020 rows");
		// the same shape for two authors fits (2 + 200 + 20,000), for three it does not
		assertThat(select("authors", params("select", "name,posts!author_id(title,comments(body))", "limit", "2"))).isNotNull();
		assertThatThrownBy(() -> select("authors", params("select", "name,posts!author_id(title,comments(body))", "limit", "3")))
			.hasMessageContaining("30303");
		// to-one expansions do not multiply, and one to-many level at the default page size is fine
		assertThat(select("comments", params("select", "body,posts(title,authors!author_id(name))", "limit", "100"))).isNotNull();
		assertThat(select("posts", params("select", "title,comments(body)", "limit", "99"))).isNotNull();
	}

	@Test
	void ambiguousRelationNeedsAHint() {
		// posts has two columns pointing at authors
		assertThatThrownBy(() -> select("posts", params("select", "title,authors(name)")))
			.hasMessageContaining("more than one column");
		assertThat(select("posts", params("select", "title,authors!editor_id(name)")).sql())
			.contains("t1.\"id\" = t0.\"editor_id\"");
		assertThatThrownBy(() -> select("posts", params("select", "authors!title(name)")))
			.hasMessageContaining("no relation");
	}

	@Test
	void validatorRejectsWhatDoesNotExistOrDoesNotFit() {
		assertThatThrownBy(() -> select("nope", params())).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("select", "nope"))).hasMessageContaining("no column");
		assertThatThrownBy(() -> select("posts", params("nope", "eq.1"))).hasMessageContaining("no column");
		assertThatThrownBy(() -> select("posts", params("order", "nope"))).hasMessageContaining("no column");
		assertThatThrownBy(() -> select("posts", params("select", "comments(nope)"))).hasMessageContaining("no column");
		assertThatThrownBy(() -> select("authors", params("select", "comments(body)"))).hasMessageContaining("no relation");
		assertThatThrownBy(() -> select("posts", params("select", "posts(title)"))).hasMessageContaining("itself");
		assertThatThrownBy(() -> select("posts", params("title", "gt.5", "meta", "eq.1"))).hasMessageContaining("jsonb");
		assertThatThrownBy(() -> select("posts", params("priority", "like.*1*"))).hasMessageContaining("cannot be used");
		assertThatThrownBy(() -> select("posts", params("published", "gt.true"))).hasMessageContaining("cannot be used");
		assertThatThrownBy(() -> select("posts", params("title", "is.true"))).hasMessageContaining("cannot be used");
		assertThatThrownBy(() -> select("posts", params("title", "is.maybe"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("title", "contains.x"))).hasMessageContaining("unknown operator");
		assertThatThrownBy(() -> select("posts", params("priority", "in.1,2"))).hasMessageContaining("brackets");
		assertThatThrownBy(() -> select("posts", params("order", "meta"))).hasMessageContaining("jsonb");
		assertThatThrownBy(() -> select("posts", params("order", "title.sideways"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("limit", "0"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("limit", "101"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("offset", "-1"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> select("posts", params("select", "comments(body),comments(id)")))
			.hasMessageContaining("twice");
	}

	@Test
	void insertOneRowAndABatch() {
		CompiledSql one = SqlBuilder.insert(PROJECT, LIMITS,
				validator().insert("posts", Map.of("title", "hello")));
		assertThat(one.sql()).isEqualTo("with changed as (insert into " + POSTS + " (\"title\") values (cast(? as text))"
				+ " returning \"id\", \"created_at\", \"title\", \"priority\", \"published\", \"meta\", \"author_id\","
				+ " \"editor_id\") select row_to_json(changed)::text from changed");
		assertThat(one.parameters()).containsExactly("hello");

		CompiledSql batch = SqlBuilder.insert(PROJECT, LIMITS,
				validator().insert("authors", List.of(Map.of("name", "a"), Map.of("name", "b"))));
		assertThat(batch.sql()).contains("values (cast(? as text)), (cast(? as text)) returning").contains("json_agg");
		assertThat(batch.parameters()).containsExactly("a", "b");

		assertThat(SqlBuilder.insert(PROJECT, LIMITS, validator().insert("authors", Map.of())).sql())
			.contains("insert into \"p_0123456789ab\".\"authors\" default values returning");
	}

	@Test
	void insertRejectsBadBodies() {
		assertThatThrownBy(() -> validator().insert("posts", "text")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator().insert("posts", List.of())).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator().insert("posts", List.of("x"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator().insert("posts", Map.of("nope", 1))).hasMessageContaining("no column");
		assertThatThrownBy(() -> validator().insert("posts", Map.of("id", "x"))).hasMessageContaining("automatically");
		assertThatThrownBy(() -> validator().insert("posts", Map.of("title", Map.of("a", 1))))
			.hasMessageContaining("plain value");
		assertThatThrownBy(() -> validator().insert("authors", List.of(Map.of("name", "a"), Map.of())))
			.hasMessageContaining("same fields");
		assertThatThrownBy(() -> validator().insert("authors", java.util.Collections.nCopies(101, Map.of("name", "a"))))
			.hasMessageContaining("at most 100");
	}

	@Test
	void updateAndDeleteNeedFiltersAndUseThem() {
		QueryParser parser = new QueryParser(LIMITS);
		CompiledSql update = SqlBuilder.update(PROJECT, LIMITS,
				validator().update("posts", parser.parse(params("priority", "lt.3")), Map.of("published", true)));
		assertThat(update.sql()).startsWith("with changed as (update " + POSTS + " as t0 set \"published\" = cast(? as boolean)"
				+ " where t0.\"priority\" < cast(? as integer) returning t0.\"id\"");
		assertThat(update.parameters()).containsExactly("true", "3");

		CompiledSql delete = SqlBuilder.delete(PROJECT, LIMITS,
				validator().delete("posts", parser.parse(params("id", "eq.abc"))));
		assertThat(delete.sql()).startsWith("with changed as (delete from " + POSTS + " as t0 where t0.\"id\" = cast(? as uuid) returning");

		assertThatThrownBy(() -> validator().delete("posts", parser.parse(params())))
			.hasMessageContaining("at least one filter");
		assertThatThrownBy(() -> validator().update("posts", parser.parse(params()), Map.of("title", "x")))
			.hasMessageContaining("at least one filter");
		assertThatThrownBy(() -> validator().delete("posts", parser.parse(params("limit", "1", "id", "eq.x"))))
			.hasMessageContaining("only filters");
		assertThatThrownBy(() -> validator().update("posts", parser.parse(params("id", "eq.x")), Map.of()))
			.hasMessageContaining("at least one field");
	}

	@Test
	void nullValuesAreBoundAsNull() {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", null);
		assertThat(SqlBuilder.insert(PROJECT, LIMITS, validator().insert("posts", body)).parameters())
			.containsExactly((Object) null);
	}

}
