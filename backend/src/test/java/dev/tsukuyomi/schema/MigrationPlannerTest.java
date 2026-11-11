package dev.tsukuyomi.schema;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.SchemaOperation.AddColumn;
import dev.tsukuyomi.schema.SchemaOperation.ColumnSpec;
import dev.tsukuyomi.schema.SchemaOperation.CreateTable;
import dev.tsukuyomi.schema.SchemaOperation.DropColumn;
import dev.tsukuyomi.schema.SchemaOperation.DropTable;
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
			.contains("\"id\" uuid primary key default gen_random_uuid()")
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
	void emptyMigrationIsRejected() {
		assertThatThrownBy(() -> planner.plan(project, Catalog.empty(), List.of())).isInstanceOf(ApiException.class);
	}

}
