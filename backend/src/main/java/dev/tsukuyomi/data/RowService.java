package dev.tsukuyomi.data;

import java.util.List;
import java.util.Map;

import dev.tsukuyomi.query.CompiledSql;
import dev.tsukuyomi.query.QueryLimits;
import dev.tsukuyomi.query.QueryValidator;
import dev.tsukuyomi.query.SqlBuilder;
import dev.tsukuyomi.query.ast.SelectQuery;
import dev.tsukuyomi.query.parse.QueryParser;
import dev.tsukuyomi.query.parse.RawQuery;
import dev.tsukuyomi.schema.CatalogService;
import dev.tsukuyomi.tenancy.ProjectRef;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantTransaction;

import tools.jackson.databind.ObjectMapper;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Reads and writes rows of user-designed tables by running a request through
 * the query compiler and executing the result as the caller's identity:
 *
 *   request -> QueryParser -> QueryValidator -> SqlBuilder -> TenantTransaction
 *   (text)     (structure)    (trusted AST)     (SQL + binds)  (Postgres role + RLS)
 */
@Service
public class RowService {

	/** @param total number of rows matching the filters, or null if not requested */
	public record Page(String json, Long total) {
	}

	private final CatalogService catalogs;

	private final TenantTransaction tenant;

	private final ObjectMapper json;

	private final QueryLimits limits = QueryLimits.DEFAULT;

	private final QueryParser parser = new QueryParser(limits);

	RowService(CatalogService catalogs, TenantTransaction tenant, ObjectMapper json) {
		this.catalogs = catalogs;
		this.tenant = tenant;
		this.json = json;
	}

	public Page select(TenantIdentity identity, String table, Map<String, List<String>> parameters, boolean count) {
		ProjectRef project = identity.project();
		SelectQuery query = validator(project).select(table, parser.parse(parameters));
		CompiledSql rows = SqlBuilder.select(project, limits, query);
		CompiledSql total = count ? SqlBuilder.count(project, limits, query) : null;
		// Both statements run in one transaction, so the count matches the page.
		return run(identity, jdbc -> new Page(
				jdbc.queryForObject(rows.sql(), String.class, rows.parameters().toArray()),
				total == null ? null : jdbc.queryForObject(total.sql(), Long.class, total.parameters().toArray())));
	}

	/** @return the inserted row (object body) or rows (array body) as JSON */
	public String insert(TenantIdentity identity, String table, Object body) {
		ProjectRef project = identity.project();
		return execute(identity, SqlBuilder.insert(project, limits, validator(project).insert(table, body)));
	}

	/** @return the changed rows as a JSON array */
	public String update(TenantIdentity identity, String table, Map<String, List<String>> parameters, Object body) {
		ProjectRef project = identity.project();
		RawQuery raw = parser.parse(parameters);
		return execute(identity, SqlBuilder.update(project, limits, validator(project).update(table, raw, body)));
	}

	/** @return the deleted rows as a JSON array */
	public String delete(TenantIdentity identity, String table, Map<String, List<String>> parameters) {
		ProjectRef project = identity.project();
		RawQuery raw = parser.parse(parameters);
		return execute(identity, SqlBuilder.delete(project, limits, validator(project).delete(table, raw)));
	}

	private QueryValidator validator(ProjectRef project) {
		return new QueryValidator(catalogs.get(project.id()), limits, json::writeValueAsString);
	}

	private String execute(TenantIdentity identity, CompiledSql compiled) {
		return run(identity, jdbc -> jdbc.queryForObject(compiled.sql(), String.class, compiled.parameters().toArray()));
	}

	private <T> T run(TenantIdentity identity, TenantTransaction.TenantWork<T> work) {
		try {
			return tenant.execute(identity, work);
		}
		catch (DataAccessException e) {
			throw DataErrors.translate(e);
		}
	}

}
