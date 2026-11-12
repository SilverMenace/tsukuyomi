package dev.tsukuyomi.query;

import java.util.List;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.query.parse.QueryParser;
import dev.tsukuyomi.query.parse.RawQuery;
import dev.tsukuyomi.query.parse.RawQuery.RawFilter;
import dev.tsukuyomi.query.parse.RawQuery.RawOrder;
import dev.tsukuyomi.query.parse.RawQuery.RawSelectItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.tsukuyomi.query.Fixtures.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParserTest {

	private final QueryParser parser = new QueryParser(Fixtures.LIMITS);

	@Test
	void emptyQueryHasNoParts() {
		RawQuery query = parser.parse(params());
		assertThat(query.select()).isEmpty();
		assertThat(query.filters()).isEmpty();
		assertThat(query.order()).isEmpty();
		assertThat(query.limit()).isNull();
		assertThat(query.offset()).isNull();
	}

	@Test
	void parsesNestedSelect() {
		RawQuery query = parser.parse(params("select", "id,title,authors!author_id(name),comments(id,posts(*))"));

		assertThat(query.select()).extracting(RawSelectItem::name).containsExactly("id", "title", "authors", "comments");
		RawSelectItem authors = query.select().get(2);
		assertThat(authors.hint()).isEqualTo("author_id");
		assertThat(authors.children()).extracting(RawSelectItem::name).containsExactly("name");
		RawSelectItem comments = query.select().get(3);
		assertThat(comments.children().get(1).isEmbed()).isTrue();
		assertThat(comments.children().get(1).children().get(0).isStar()).isTrue();
	}

	@Test
	void parsesFiltersIncludingNotAndRepeats() {
		RawQuery query = parser.parse(
				params("priority", "gte.3", "priority", "not.lt.10", "title", "eq.a.b.c", "id", "in.(1,2)"));

		assertThat(query.filters()).containsExactly(new RawFilter("priority", false, "gte", "3"),
				new RawFilter("priority", true, "lt", "10"), new RawFilter("title", false, "eq", "a.b.c"),
				new RawFilter("id", false, "in", "(1,2)"));
	}

	@Test
	void parsesOrderLimitOffset() {
		RawQuery query = parser.parse(params("order", "created_at.desc.nullslast,title", "limit", "5", "offset", "10"));

		assertThat(query.order()).containsExactly(new RawOrder("created_at", "desc", "nullslast"),
				new RawOrder("title", null, null));
		assertThat(query.limit()).isEqualTo(5);
		assertThat(query.offset()).isEqualTo(10);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", ",", "id,", ",id", "a(", "a(b", "a)", "a(b))", "a()", "a!(b)", "a!b", "a(b(c(d)))",
			"a((b))", "a!b!c(d)" })
	void rejectsMalformedSelect(String select) {
		assertThatThrownBy(() -> parser.parse(params("select", select))).isInstanceOf(ApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "5", "eq", ".5", "not.5", "not..5" })
	void rejectsMalformedFilter(String value) {
		assertThatThrownBy(() -> parser.parse(params("title", value))).isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsBadNumbersDuplicatesAndOversizedInput() {
		assertThatThrownBy(() -> parser.parse(params("limit", "ten"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> parser.parse(params("limit", "1; drop table x"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> parser.parse(params("limit", "1", "limit", "2"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> parser.parse(params("order", "a.asc.nullslast.extra"))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> parser.parse(params("select", "x".repeat(3000)))).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> parser.parse(params("title", "eq." + "x".repeat(3000)))).isInstanceOf(ApiException.class);

		String[] many = new String[21 * 2];
		for (int i = 0; i < 21; i++) {
			many[i * 2] = "title";
			many[i * 2 + 1] = "eq." + i;
		}
		assertThatThrownBy(() -> parser.parse(params(many))).hasMessageContaining("too many filters");
		assertThat(List.of(many)).hasSize(42);
	}

}
