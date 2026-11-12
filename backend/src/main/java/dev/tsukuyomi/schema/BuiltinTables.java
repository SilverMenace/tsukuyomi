package dev.tsukuyomi.schema;

import java.util.List;
import java.util.Map;

import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantRole;

/**
 * The "users" table every project starts with.
 *
 * It holds the public profile of each end user (id, email), so the owner's
 * tables can point at users with a foreign key and rules can say "the row's
 * owner". Password hashes are NOT here; they live in tsk_platform.end_user.
 *
 * Rows are created only by signup (AuthService), never through the data API:
 * tenant roles are granted select and update, not insert or delete.
 */
public final class BuiltinTables {

	public static final String USERS = "users";

	private BuiltinTables() {
	}

	/** The catalog entry. By default a logged-in user can read only their own row. */
	public static TableDef users() {
		return new TableDef(USERS,
				List.of(new ColumnDef("id", ColumnType.UUID, false, true, true),
						new ColumnDef("created_at", ColumnType.TIMESTAMPTZ, false, false, true),
						new ColumnDef("email", ColumnType.TEXT, false, true, true)),
				Map.of(RuleOperation.SELECT, new Rule(RuleKind.OWNER, "id")), true);
	}

	/** The DDL. Keep in step with the backfill in V3__end_users_and_rules.sql. */
	public static List<String> usersStatements(ProjectRef project) {
		String table = "\"" + project.schemaName() + "\".\"users\"";
		String anon = "\"" + project.roleName(TenantRole.ANON) + "\"";
		String user = "\"" + project.roleName(TenantRole.USER) + "\"";
		String service = "\"" + project.roleName(TenantRole.SERVICE) + "\"";
		return List.of("create table " + table + " (\n" + "  \"id\" uuid primary key default uuidv7(),\n"
				+ "  \"created_at\" timestamptz not null default now(),\n"
				+ "  \"email\" text not null constraint \"users_email_key\" unique\n)",
				"alter table " + table + " enable row level security",
				"alter table " + table + " force row level security",
				"grant select, update on " + table + " to " + anon + ", " + user + ", " + service,
				"create policy \"service_all\" on " + table + " for all to " + service + " using (true) with check (true)",
				"create policy \"rule_select\" on " + table + " for select to " + user + " using (\"id\" = tsk.uid())",
				ChangeTrigger.statement(table));
	}

}
