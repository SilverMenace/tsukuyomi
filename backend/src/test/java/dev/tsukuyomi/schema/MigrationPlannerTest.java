package dev.tsukuyomi.schema;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.SchemaOperation.AddColumn;
import dev.tsukuyomi.schema.SchemaOperation.ColumnSpec;
import dev.tsukuyomi.schema.SchemaOperation.CreateTable;
import dev.tsukuyomi.schema.SchemaOperation.DropColumn;
import dev.tsukuyomi.schema.SchemaOperation.DropTable;
import dev.tsukuyomi.schema.SchemaOperation.SetRule;
import dev.tsukuyomi.tenancy.ProjectRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planner is pure logic, so these tests need no database and run in milliseconds. */
class MigrationPlannerTest {

	private final ProjectRef project = new ProjectRef(UUID.randomUUID(), "p_0123456789ab");

	private final MigrationPlanner planner = new MigrationPlanner(3, 5);

	private static ColumnSpec column(String name, ColumnType type) {
		return new ColumnSpec(name, type, null, null);
	}

	@Test
	void createTableProducesTableRlsGrantsAndServicePolicy() {
		var plan = planner.plan(project, Catalog.empty(), List.of(new CreateTable("notes",
				List.of(new ColumnSpec("title", ColumnType.TEXT, false, null),
						new ColumnSpec("slug", ColumnType.TEXT, null, true)))));

		assertThat(plan.statements()).hasSize(5);
		assertThat(plan.statements().get(0)).startsWith("create table \"p_0123456789ab\".\"notes\"")
			.contains("\"id\" uuid primary key default uuidv7()")
			.contains("\"title\" text not null")
			.contains("\"slug\" text constraint \"notes_slug_key\" unique");
		assertThat(plan.statements().get(1)).endsWith("enable row level security");
		assertThat(plan.statements().get(2)).endsWith("force row level security");
		assertThat(plan.statements().get(3)).contains("\"p_0123456789ab_anon\"", "\"p_0123456789ab_user\"",
				"\"p_0123456789ab_service\"");
		assertThat(plan.statements().get(4)).contains("to \"p_0123456789ab_service\" using (true)");

		TableDef notes = plan.result().table("notes").orElseThrow();
		assertThat(notes.columns()).extracting(ColumnDef::name).containsExactly("id", "created_at", "title", "slug");
		assertThat(plan.result().version()).isEqualTo(1);
	}

	@Test
	void laterOperationsSeeEarlierOnesInTheSameMigration() {
		var plan = planner.plan(project, Catalog.empty(),
				List.of(new CreateTable("notes", List.of()), new AddColumn("notes", column("body", ColumnType.TEXT)),
						new DropColumn("notes", "body"), new DropTable("notes")));

		assertThat(plan.result().tables()).isEmpty();
		assertThat(plan.statements().getLast()).isEqualTo("drop table \"p_0123456789ab\".\"notes\"");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Notes", "1notes", "no tes", "notes;drop table x", "notes\"", "notes'--", "нотес", "",
			"a_name_that_is_longer_than_28_chars", "users", "tsk_internal", "pg_class" })
	void rejectsUnsafeOrReservedTableNames(String name) {
		assertThatThrownBy(() -> planner.plan(project, Catalog.empty(), List.of(new CreateTable(name, List.of()))))
			.isInstanceOf(ApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "id", "created_at", "Title", "a b", "x\" text); drop schema public cascade; --" })
	void rejectsUnsafeOrSystemColumnNames(String name) {
		assertThatThrownBy(() -> planner.plan(project, Catalog.empty(),
				List.of(new CreateTable("notes", List.of(column(name, ColumnType.TEXT))))))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsDuplicatesUnknownTargetsAndSystemColumnDrops() {
		Catalog one = planner.plan(project, Catalog.empty(),
				List.of(new CreateTable("notes", List.of(column("title", ColumnType.TEXT)))))
			.result();

		assertThatThrownBy(() -> planner.plan(project, one, List.of(new CreateTable("notes", List.of()))))
			.hasMessageContaining("already exists");
		assertThatThrownBy(() -> planner.plan(project, one, List.of(new AddColumn("notes", column("title", ColumnType.TEXT)))))
			.hasMessageContaining("already has a column");
		assertThatThrownBy(() -> planner.plan(project, one, List.of(new AddColumn("nope", column("a", ColumnType.TEXT)))))
			.hasMessageContaining("no table named");
		assertThatThrownBy(() -> planner.plan(project, one, List.of(new DropColumn("notes", "id"))))
			.hasMessageContaining("cannot be removed");
		assertThatThrownBy(() -> planner.plan(project, one, List.of(new DropColumn("notes", "missing"))))
			.hasMessageContaining("has no column");
	}

	@Test
	void enforcesQuotas() {
		Catalog full = planner.plan(project, Catalog.empty(), List.of(new CreateTable("a", List.of()),
				new CreateTable("b", List.of()), new CreateTable("c", List.of())))
			.result();
		assertThatThrownBy(() -> planner.plan(project, full, List.of(new CreateTable("d", List.of()))))
			.hasMessageContaining("at most 3 tables");

		// 2 system columns + 3 = 5 allowed, the 4th user column is one too many
		assertThatThrownBy(() -> planner.plan(project, Catalog.empty(),
				List.of(new CreateTable("wide", List.of(column("c1", ColumnType.TEXT), column("c2", ColumnType.TEXT),
						column("c3", ColumnType.TEXT), column("c4", ColumnType.TEXT))))))
			.hasMessageContaining("at most 5 columns");
	}

	@Test
	void referenceColumnGetsForeignKeyAndIndex() {
		var plan = planner.plan(project, Catalog.empty(),
				List.of(new CreateTable("authors", List.of()), new CreateTable("posts",
						List.of(new ColumnSpec("author_id", ColumnType.UUID, null, null, "authors", OnDelete.CASCADE)))));

		assertThat(plan.sql()).contains("\"author_id\" uuid constraint \"posts_author_id_fkey\" references"
				+ " \"p_0123456789ab\".\"authors\" (\"id\") on delete cascade")
			.contains("create index \"posts_author_id_idx\" on \"p_0123456789ab\".\"posts\" (\"author_id\")");
		ColumnDef column = plan.result().table("posts").orElseThrow().column("author_id").orElseThrow();
		assertThat(column.references()).isEqualTo("authors");
		assertThat(column.onDelete()).isEqualTo(OnDelete.CASCADE);

		// default action, and a self-reference added after the table exists
		var more = planner.plan(project, plan.result(), List.of(
				new AddColumn("posts", new ColumnSpec("parent_id", ColumnType.UUID, null, null, "posts", null))));
		assertThat(more.sql()).contains("on delete restrict").contains("create index \"posts_parent_id_idx\"");
	}

	@Test
	void rejectsInvalidReferences() {
		Catalog authors = planner.plan(project, Catalog.empty(), List.of(new CreateTable("authors", List.of()))).result();

		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("author_id", ColumnType.TEXT, null, null, "authors", null))))))
			.hasMessageContaining("must be of type uuid");
		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("author_id", ColumnType.UUID, null, null, "nope", null))))))
			.hasMessageContaining("no table named 'nope'");
		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("author_id", ColumnType.UUID, null, null, "x\"; drop table authors; --", null))))))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("parent_id", ColumnType.UUID, null, null, "posts", null))))))
			.hasMessageContaining("cannot reference itself while it is being created");
		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("author_id", ColumnType.UUID, false, null, "authors", OnDelete.SET_NULL))))))
			.hasMessageContaining("needs the column to be nullable");
		assertThatThrownBy(() -> planner.plan(project, authors, List.of(new CreateTable("posts",
				List.of(new ColumnSpec("title", ColumnType.TEXT, null, null, null, OnDelete.CASCADE))))))
			.hasMessageContaining("only applies");
	}

	@Test
	void referencedTableCannotBeDroppedFirst() {
		Catalog both = planner.plan(project, Catalog.empty(), List.of(new CreateTable("authors", List.of()),
				new CreateTable("posts", List.of(new ColumnSpec("author_id", ColumnType.UUID, null, null, "authors", null)))))
			.result();

		assertThatThrownBy(() -> planner.plan(project, both, List.of(new DropTable("authors"))))
			.hasMessageContaining("posts.author_id");
		// removing the pointing column first makes it possible, in the same migration
		assertThat(planner.plan(project, both, List.of(new DropColumn("posts", "author_id"), new DropTable("authors")))
			.result()
			.tables()).extracting(TableDef::name).containsExactly("posts");
	}

	@Test
	void columnNamesUsedAsQueryParametersAreReserved() {
		for (String name : List.of("select", "order", "limit", "offset")) {
			assertThatThrownBy(() -> planner.plan(project, Catalog.empty(),
					List.of(new CreateTable("t", List.of(column(name, ColumnType.TEXT))))))
				.hasMessageContaining("reserved");
		}
	}

	@Test
	void rulesBecomeRowLevelSecurityPolicies() {
		Catalog start = new Catalog(0, List.of(BuiltinTables.users()));
		var plan = planner.plan(project, start, List.of(
				new CreateTable("notes", List.of(new ColumnSpec("owner_id", ColumnType.UUID, null, null, "users", null))),
				new SetRule("notes", RuleOperation.SELECT, RuleKind.ANYONE, null),
				new SetRule("notes", RuleOperation.INSERT, RuleKind.OWNER, "owner_id"),
				new SetRule("notes", RuleOperation.UPDATE, RuleKind.OWNER, "owner_id"),
				new SetRule("notes", RuleOperation.DELETE, RuleKind.AUTHENTICATED, null)));
		String notes = "\"p_0123456789ab\".\"notes\"";

		assertThat(plan.statements()).contains(
				"drop policy if exists \"rule_select\" on " + notes,
				"create policy \"rule_select\" on " + notes
						+ " for select to \"p_0123456789ab_anon\", \"p_0123456789ab_user\" using (true)",
				"create policy \"rule_insert\" on " + notes
						+ " for insert to \"p_0123456789ab_user\" with check (\"owner_id\" = tsk.uid())",
				"create policy \"rule_update\" on " + notes + " for update to \"p_0123456789ab_user\""
						+ " using (\"owner_id\" = tsk.uid()) with check (\"owner_id\" = tsk.uid())",
				"create policy \"rule_delete\" on " + notes + " for delete to \"p_0123456789ab_user\" using (true)",
				"alter table " + notes + " alter column \"owner_id\" set default tsk.uid()");
		TableDef table = plan.result().table("notes").orElseThrow();
		assertThat(table.rule(RuleOperation.INSERT)).isEqualTo(new Rule(RuleKind.OWNER, "owner_id"));

		// back to nobody: the policy is dropped and nothing replaces it
		var closed = planner.plan(project, plan.result(), List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.NOBODY, null)));
		assertThat(closed.statements()).containsExactly("drop policy if exists \"rule_select\" on " + notes);
		assertThat(closed.result().table("notes").orElseThrow().rule(RuleOperation.SELECT)).isEqualTo(Rule.NOBODY);
	}

	@Test
	void rejectsInvalidRulesAndProtectsTheUsersTable() {
		Catalog start = planner.plan(project, new Catalog(0, List.of(BuiltinTables.users())), List.of(new CreateTable("notes",
				List.of(column("title", ColumnType.TEXT), new ColumnSpec("owner_id", ColumnType.UUID, null, null, "users", null)))))
			.result();

		assertThatThrownBy(() -> planner.plan(project, start, List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.OWNER, null))))
			.hasMessageContaining("needs ownerColumn");
		assertThatThrownBy(() -> planner.plan(project, start, List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.OWNER, "title"))))
			.hasMessageContaining("must be of type uuid");
		assertThatThrownBy(() -> planner.plan(project, start,
				List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.OWNER, "owner_id = tsk.uid() or true"))))
			.hasMessageContaining("needs ownerColumn");
		assertThatThrownBy(() -> planner.plan(project, start, List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.ANYONE, "owner_id"))))
			.hasMessageContaining("only applies");
		assertThatThrownBy(() -> planner.plan(project, start, List.of(new SetRule("nope", RuleOperation.SELECT, RuleKind.ANYONE, null))))
			.hasMessageContaining("no table named");

		assertThatThrownBy(() -> planner.plan(project, start, List.of(new DropTable("users")))).hasMessageContaining("built in");
		assertThatThrownBy(() -> planner.plan(project, start, List.of(new SetRule("users", RuleOperation.DELETE, RuleKind.OWNER, "id"))))
			.hasMessageContaining("signup only");
		assertThatThrownBy(() -> planner.plan(project, start, List.of(new CreateTable("users", List.of()))))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> planner.plan(project, start,
				List.of(new AddColumn("users", new ColumnSpec("name", ColumnType.TEXT, false, null)))))
			.hasMessageContaining("must be nullable");
		// profile columns and a wider read rule on users are allowed
		var profile = planner.plan(project, start, List.of(new AddColumn("users", column("name", ColumnType.TEXT)),
				new SetRule("users", RuleOperation.SELECT, RuleKind.AUTHENTICATED, null),
				new SetRule("users", RuleOperation.UPDATE, RuleKind.OWNER, "id")));
		assertThat(profile.sql()).contains("for update to \"p_0123456789ab_user\" using (\"id\" = tsk.uid())")
			.doesNotContain("alter column \"id\" set default");
		assertThat(profile.result().table("users").orElseThrow().builtin()).isTrue();

		// a column used by a rule cannot be dropped
		Catalog ruled = planner.plan(project, start, List.of(new SetRule("notes", RuleOperation.SELECT, RuleKind.OWNER, "owner_id"))).result();
		assertThatThrownBy(() -> planner.plan(project, ruled, List.of(new DropColumn("notes", "owner_id"))))
			.hasMessageContaining("change the rule first");
	}

	@Test
	void emptyMigrationIsRejected() {
		assertThatThrownBy(() -> planner.plan(project, Catalog.empty(), List.of())).isInstanceOf(ApiException.class);
	}

}
