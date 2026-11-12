package dev.tsukuyomi.schema;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.CatalogRepository.MigrationRecord;
import dev.tsukuyomi.tenancy.ProjectRef;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies schema operations to a project: plan, run the DDL, record it. */
@Service
public class MigrationService {

	/** What a migration did (or, for a dry run, would do). */
	public record Result(int version, String sql, boolean applied) {
	}

	private final CatalogRepository repository;

	private final CatalogService catalogs;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	private final ObjectMapper json;

	private final MigrationPlanner planner;

	MigrationService(CatalogRepository repository, CatalogService catalogs, JdbcTemplate jdbc,
			TransactionTemplate transaction, ObjectMapper json,
			@Value("${tsukuyomi.quota.tables-per-project:15}") int maxTables,
			@Value("${tsukuyomi.quota.columns-per-table:30}") int maxColumns,
			@Value("${tsukuyomi.quota.rows-per-table:10000}") int maxRows) {
		this.repository = repository;
		this.catalogs = catalogs;
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.json = json;
		this.planner = new MigrationPlanner(maxTables, maxColumns, maxRows);
	}

	/**
	 * Runs the DDL, the catalog update and the history row in ONE transaction.
	 * Postgres can roll back DDL, so a migration either happens completely or
	 * not at all. A dry run does the same work and then rolls back on purpose,
	 * which proves the SQL is valid without changing anything.
	 */
	public Result apply(ProjectRef project, List<SchemaOperation> operations, boolean dryRun) {
		try {
			Result result = transaction.execute(status -> {
				repository.lockVersion(project.id());
				Catalog current = repository.load(project.id());
				MigrationPlanner.Plan plan = planner.plan(project, current, operations);
				plan.statements().forEach(jdbc::execute);
				repository.replace(project.id(), plan.result());
				repository.insertMigration(project.id(), plan.result().version(), json.writeValueAsString(operations),
						plan.sql());
				if (dryRun) {
					status.setRollbackOnly();
				}
				return new Result(plan.result().version(), plan.sql(), !dryRun);
			});
			if (!dryRun) {
				catalogs.evict(project.id());
			}
			return result;
		}
		catch (DataAccessException e) {
			// The database refused the change, e.g. adding a NOT NULL column to a
			// table that already has rows. The owner needs to see why.
			throw ApiException.badRequest("migration_failed", rootMessage(e));
		}
	}

	public List<MigrationRecord> history(ProjectRef project) {
		return repository.migrations(project.id());
	}

	private static String rootMessage(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		String message = root.getMessage() == null ? "the database rejected this change" : root.getMessage();
		return message.lines().findFirst().orElse(message);
	}

}
