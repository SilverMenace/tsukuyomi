package dev.tsukuyomi.platform;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.common.Tokens;
import dev.tsukuyomi.schema.BuiltinTables;
import dev.tsukuyomi.schema.Catalog;
import dev.tsukuyomi.schema.CatalogRepository;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantRole;
import dev.tsukuyomi.tenancy.TenantTransaction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Creating and deleting projects, including their Postgres schema and roles. */
@Service
public class ProjectService {

	/** A new project together with its keys, which are shown exactly once. */
	public record Created(Project project, String anonKey, String serviceKey) {
	}

	private final ProjectRepository projects;

	private final CatalogRepository catalogs;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final int maxProjects;

	ProjectService(ProjectRepository projects, CatalogRepository catalogs, JdbcTemplate jdbc,
			TransactionTemplate transaction,
			@Value("${tsukuyomi.quota.projects-per-account:3}") int maxProjects) {
		this.projects = projects;
		this.catalogs = catalogs;
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.maxProjects = maxProjects;
	}

	public List<Project> list(Account account) {
		return projects.findByAccount(account.id());
	}

	public Project get(Account account, UUID projectId) {
		return projects.findOwned(projectId, account.id()).orElseThrow(() -> ApiException.notFound("no such project"));
	}

	public List<ApiKeyInfo> keys(Account account, UUID projectId) {
		return projects.findKeys(get(account, projectId).id());
	}

	/**
	 * Creates the project row, its schema, its three roles, its users table and
	 * its two API keys in one transaction. Postgres DDL is transactional, so if any step fails
	 * nothing is left behind: no orphan schema, no orphan role.
	 */
	public Created create(Account account, String rawName) {
		String name = rawName.trim();
		return transaction.execute(status -> {
			projects.lockAccount(account.id());
			if (projects.countByAccount(account.id()) >= maxProjects) {
				throw ApiException.conflict("quota_projects", "this account already has " + maxProjects + " projects");
			}
			if (projects.nameExists(account.id(), name)) {
				throw ApiException.conflict("name_taken", "you already have a project with this name");
			}
			String schema = "p_" + Tokens.randomHex(6);
			Project project = projects.insert(account.id(), name, schema, Tokens.randomBytes(32));
			ProjectRef ref = project.ref(); // validates the schema name before it is embedded below

			jdbc.execute("create schema \"" + ref.schemaName() + "\"");
			for (TenantRole role : TenantRole.values()) {
				String roleName = "\"" + ref.roleName(role) + "\"";
				jdbc.execute("create role " + roleName + " nologin");
				jdbc.execute("grant usage on schema \"" + ref.schemaName() + "\" to " + roleName);
				// Let the data plane's login role switch into this role, without
				// inheriting its privileges when it has not switched.
				jdbc.execute("grant " + roleName + " to " + TenantTransaction.LOGIN_ROLE
						+ " with inherit false, set true");
			}
			// Every project starts with a "users" table for its end users.
			BuiltinTables.usersStatements(ref).forEach(jdbc::execute);
			catalogs.replace(project.id(), new Catalog(0, List.of(BuiltinTables.users())));
			return new Created(project, newKey(project.id(), TenantRole.ANON), newKey(project.id(), TenantRole.SERVICE));
		});
	}

	/** Replaces a key. The old one stops working immediately. */
	public String rotateKey(Account account, UUID projectId, String kind) {
		Project project = get(account, projectId);
		TenantRole role = switch (kind) {
			case "anon" -> TenantRole.ANON;
			case "service" -> TenantRole.SERVICE;
			default -> throw ApiException.badRequest("invalid_kind", "kind must be anon or service");
		};
		return newKey(project.id(), role);
	}

	public void delete(Account account, UUID projectId) {
		ProjectRef ref = get(account, projectId).ref();
		transaction.executeWithoutResult(status -> {
			jdbc.execute("drop schema \"" + ref.schemaName() + "\" cascade");
			// Dropping the schema removed every grant these roles held, so they can go.
			for (TenantRole role : TenantRole.values()) {
				jdbc.execute("drop role \"" + ref.roleName(role) + "\"");
			}
			projects.delete(ref.id());
		});
	}

	private String newKey(UUID projectId, TenantRole role) {
		String kind = role.suffix();
		String key = "tsk_" + kind + "_" + Tokens.randomHex(24);
		projects.upsertKey(projectId, kind, Tokens.sha256(key), key.substring(0, kind.length() + 10));
		return key;
	}

}
