package dev.tsukuyomi.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.query.parse.QueryParser;
import dev.tsukuyomi.query.parse.RawQuery;

import org.junit.jupiter.api.Test;

import static dev.tsukuyomi.query.Fixtures.LIMITS;
import static dev.tsukuyomi.query.Fixtures.PROJECT;
import static dev.tsukuyomi.query.Fixtures.params;
import static dev.tsukuyomi.query.Fixtures.validator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tries to get attacker-controlled text into the SQL string.
 *
 * For every attempt there are exactly two acceptable outcomes:
 *   - the request is rejected with a 4xx ApiException, or
 *   - SQL is produced and passes {@link #assertSafe}: it is made only of the
 *     fixed skeleton, catalog identifiers and "?" placeholders.
 * Anything else (another exception type, or SQL that fails the check) is a bug.
 *
 * Part one is a hand-written corpus of classic payloads placed in every
 * position of a request. Part two is a fuzzer: tens of thousands of random
 * requests assembled from SQL metacharacters, keywords and valid fragments.
 */
class InjectionTest {

	private static final List<String> PAYLOADS = List.of("'", "''", "\"", "\\", ";", "--", "/*", "*/",
			"' or '1'='1", "' or 1=1 --", "1; drop table posts; --", "1); drop table posts; --",
			"\"; drop table posts; --", "x' union select password_hash from tsk_platform.account --",
			"title\" from tsk_platform.account --", "posts\".\"title", "t0.\"title\"", "$1", "?", "??", "%s",
			"\u0000", "\n; drop table posts", "eq.1) or (1=1", "in.(1) or 1=1", "(select 1)", "pg_sleep(10)",
			"cast(1 as text)", "title::text", "title||title", "*", "**", "(", ")", "()", ",", "!", "a!b", "a(b",
			"id,title) r; drop table posts; --", "created_at.desc; drop table posts", "desc nulls first; select 1",
			"1 union select 1", "１", "тitle", "title ", " title", "TITLE", "Title", "title\t", "ti​tle",
			"x".repeat(5000));

	private static final Set<String> KNOWN_IDENTIFIERS = Set.of("p_0123456789ab", "authors", "posts", "comments", "id",
			"created_at", "name", "title", "priority", "published", "meta", "author_id", "editor_id", "body", "post_id");

	private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

	/** After removing quoted identifiers and the one allowed literal, only this alphabet may remain. */
	private static final Pattern SKELETON = Pattern.compile("^[a-z0-9_ ,.()=<>?:]*$");

	private final QueryParser parser = new QueryParser(LIMITS);

	// ---- part one: the corpus -------------------------------------------

	@Test
	void corpusInEveryReadPosition() {
		for (String payload : PAYLOADS) {
			attemptSelect("posts", params("select", payload), payload);
			attemptSelect("posts", params("select", "id," + payload), payload);
			attemptSelect("posts", params("select", "comments(" + payload + ")"), payload);
			attemptSelect("posts", params("select", payload + "(id)"), payload);
			attemptSelect("posts", params("select", "authors!" + payload + "(id)"), payload);
			attemptSelect("posts", params("order", payload), payload);
			attemptSelect("posts", params("order", "title." + payload), payload);
			attemptSelect("posts", params("order", "title.desc." + payload), payload);
			attemptSelect("posts", params("limit", payload), payload);
			attemptSelect("posts", params("offset", payload), payload);
			attemptSelect("posts", params(payload, "eq.1"), payload); // as a column name
			attemptSelect("posts", params("title", payload), payload); // as a whole filter
			attemptSelect("posts", params("title", payload + ".x"), payload); // as an operator
			attemptSelect("posts", params("title", "not." + payload + ".x"), payload);
			attemptSelect("posts", params("priority", "in.(" + payload + ")"), payload);
			attemptSelect("posts", params("published", "is." + payload), payload);
			attemptSelect(payload, params(), payload); // as the table name
			// As a VALUE the payload is legitimate data: it must compile, and stay out of the SQL.
			for (String operator : List.of("eq", "neq", "gt", "like", "ilike")) {
				CompiledSql compiled = attemptSelect("posts", params("title", operator + "." + payload), payload);
				if (payload.length() <= LIMITS.maxValueLength() - 10) {
					assertThat(compiled).as("value payload %s must be accepted", payload).isNotNull();
					assertThat(compiled.parameters()).contains(operator.contains("like") ? payload.replace('*', '%') : payload);
				}
			}
		}
	}

	@Test
	void corpusInEveryWritePosition() {
		for (String payload : PAYLOADS) {
			attemptWrite(() -> SqlBuilder.insert(PROJECT, LIMITS, validator().insert("posts", Map.of(payload, "x"))), payload);
			attemptWrite(() -> SqlBuilder.insert(PROJECT, LIMITS, validator().insert(payload, Map.of("title", "x"))), payload);
			attemptWrite(() -> SqlBuilder.update(PROJECT, LIMITS,
					validator().update("posts", parser.parse(params(payload, "eq.1")), Map.of("title", "x"))), payload);
			attemptWrite(() -> SqlBuilder.update(PROJECT, LIMITS,
					validator().update("posts", parser.parse(params("id", "eq.1")), Map.of(payload, "x"))), payload);
			attemptWrite(() -> SqlBuilder.delete(PROJECT, LIMITS,
					validator().delete("posts", parser.parse(params("title", payload)))), payload);

			// As body values the payload is data: accepted, and bound.
			CompiledSql insert = attemptWrite(
					() -> SqlBuilder.insert(PROJECT, LIMITS, validator().insert("posts", Map.of("title", payload))), payload);
			assertThat(insert.parameters()).containsExactly(payload);
			CompiledSql update = attemptWrite(() -> SqlBuilder.update(PROJECT, LIMITS,
					validator().update("posts", parser.parse(params("id", "eq." + "0".repeat(8))), Map.of("title", payload))),
					payload);
			assertThat(update.parameters()).contains(payload);
		}
	}

	// ---- part two: the fuzzer -------------------------------------------

	private static final List<String> FRAGMENTS = List.of("id", "title", "priority", "published", "meta", "author_id",
			"posts", "authors", "comments", "body", "post_id", "*", ",", "(", ")", "!", ".", "'", "\"", ";", "--", " ",
			"eq", "neq", "gt", "in", "is", "like", "not", "null", "true", "asc", "desc", "nullslast", "select", "union",
			"drop table", "or 1=1", "1", "0", "-1", "99999999999", "%", "_", "\\", "/*", "*/", "$1", "?", "\n", "\u0000",
			"e1", "t0", "t0.\"id\"", "\"posts\"", "::text", "||", "=", "<", ">");

	@Test
	void fuzzedRequestsNeverProduceUnsafeSql() {
		Random random = new Random(20261112L); // fixed seed: a failure can be reproduced exactly
		int compiled = 0;
		int rejected = 0;
		for (int i = 0; i < 40_000; i++) {
			List<String> pairs = new ArrayList<>();
			int parameters = random.nextInt(4);
			for (int p = 0; p < parameters; p++) {
				String name = switch (random.nextInt(6)) {
					case 0 -> "select";
					case 1 -> "order";
					case 2 -> random.nextBoolean() ? "limit" : "offset";
					case 3 -> pick(random, List.of("title", "priority", "published", "author_id", "id", "meta"));
					default -> soup(random, 1 + random.nextInt(3));
				};
				// Half the time a well-formed operator prefix, so fuzzing reaches the value position too.
				String prefix = random.nextBoolean() ? pick(random, List.of("eq.", "not.eq.", "in.(", "is.", "like.", "gt."))
						: "";
				pairs.add(name);
				pairs.add(prefix + soup(random, random.nextInt(6)));
			}
			String table = random.nextInt(10) == 0 ? soup(random, 2) : pick(random, List.of("posts", "authors", "comments"));
			if (attemptSelect(table, params(pairs.toArray(String[]::new)), null) == null) {
				rejected++;
			}
			else {
				compiled++;
			}
		}
		// The fuzzer is only meaningful if it gets past validation often enough to exercise the builder.
		assertThat(compiled).as("fuzz cases that reached the SQL builder").isGreaterThan(2_000);
		assertThat(rejected).as("fuzz cases rejected by parser or validator").isGreaterThan(2_000);
	}

	private static String soup(Random random, int pieces) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < pieces; i++) {
			text.append(pick(random, FRAGMENTS));
		}
		return text.toString();
	}

	private static String pick(Random random, List<String> options) {
		return options.get(random.nextInt(options.size()));
	}

	// ---- the oracle -----------------------------------------------------

	/** @return the compiled SQL, or null if the request was rejected with a 4xx */
	private CompiledSql attemptSelect(String table, Map<String, List<String>> parameters, String payload) {
		try {
			RawQuery raw = parser.parse(parameters);
			var query = validator().select(table, raw);
			CompiledSql rows = SqlBuilder.select(PROJECT, LIMITS, query);
			assertSafe(rows, parameters.toString());
			assertSafe(SqlBuilder.count(PROJECT, LIMITS, query), parameters.toString());
			return rows;
		}
		catch (ApiException expected) {
			assertThat(expected.status().is4xxClientError()).isTrue();
			return null;
		}
		catch (RuntimeException unexpected) {
			return fail("request " + parameters + " on table '" + table + "' caused " + unexpected, unexpected);
		}
	}

	private CompiledSql attemptWrite(java.util.function.Supplier<CompiledSql> attempt, String payload) {
		try {
			CompiledSql compiled = attempt.get();
			assertSafe(compiled, payload);
			return compiled;
		}
		catch (ApiException expected) {
			assertThat(expected.status().is4xxClientError()).isTrue();
			return null;
		}
		catch (RuntimeException unexpected) {
			return fail("payload " + payload + " caused " + unexpected, unexpected);
		}
	}

	/**
	 * The definition of "safe" used by this test, deliberately independent of
	 * how the builder works:
	 *   1. every double-quoted identifier is one we know from the catalog;
	 *   2. the only single-quoted literal is '[]', and the only "*" is in count(*);
	 *   3. what remains uses only lowercase words, digits and a few symbols, so
	 *      there is no comment marker, semicolon, quote, backslash or newline;
	 *   4. the number of "?" equals the number of bind parameters.
	 */
	static void assertSafe(CompiledSql compiled, String context) {
		String sql = compiled.sql();
		Matcher quoted = QUOTED.matcher(sql);
		while (quoted.find()) {
			assertThat(KNOWN_IDENTIFIERS).as("identifier in SQL for %s", context).contains(quoted.group(1));
		}
		String skeleton = QUOTED.matcher(sql)
			.replaceAll("")
			.replace("'[]'::json", "")
			.replace("select count(*) from", "select count from"); // the builder's own fixed text
		assertThat(skeleton).as("SQL skeleton for %s", context).matches(SKELETON);
		assertThat(skeleton).doesNotContain("--");
		long placeholders = skeleton.chars().filter(c -> c == '?').count();
		assertThat(placeholders).as("placeholders vs parameters for %s", context).isEqualTo(compiled.parameters().size());
	}

}
