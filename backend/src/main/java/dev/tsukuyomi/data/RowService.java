package dev.tsukuyomi.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.schema.CatalogService;
import dev.tsukuyomi.schema.ColumnDef;
import dev.tsukuyomi.schema.ColumnType;
import dev.tsukuyomi.schema.Identifiers;
import dev.tsukuyomi.schema.TableDef;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantTransaction;

import tools.jackson.databind.ObjectMapper;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Reads and writes rows of user-designed tables.
 *
 * M1 version: list and insert only. The full query compiler (filters, sorting,
 * expansion) replaces the SQL building in this class in M2, but the two rules
 * it follows are already the final ones:
 *
 *   1. Table and column names in SQL come from the catalog (TableDef/ColumnDef),
 *      never from the request. The request only selects WHICH catalog entry.
 *   2. Values travel as bind parameters ("?"), never as SQL text.
 */
@Service
public class RowService {

	public static final int MAX_LIMIT = 100;

	private final CatalogService catalogs;

	private final TenantTransaction tenant;

	private final ObjectMapper json;

	RowService(CatalogService catalogs, TenantTransaction tenant, ObjectMapper json) {
		this.catalogs = catalogs;
		this.tenant = tenant;
		this.json = json;
	}

	/** @return a JSON array of rows, produced by Postgres itself */
	public String list(TenantIdentity identity, String tableName, int limit, int offset) {
		TableDef table = table(identity, tableName);
		if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
			throw ApiException.badRequest("invalid_page", "limit must be 1.." + MAX_LIMIT + " and offset 0 or more");
		}
		String sql = "select coalesce(json_agg(r), '[]'::json)::text from (select " + columnList(table) + " from "
				+ qualified(identity, table) + " order by \"created_at\", \"id\" limit ? offset ?) r";
		return run(identity, jdbc -> jdbc.queryForObject(sql, String.class, limit, offset));
	}

	/** @return the inserted row as a JSON object, including generated id and created_at */
	public String insert(TenantIdentity identity, String tableName, Map<String, Object> body) {
		TableDef table = table(identity, tableName);
		List<String> names = new ArrayList<>();
		List<String> placeholders = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (Map.Entry<String, Object> field : body.entrySet()) {
			ColumnDef column = table.column(field.getKey())
				.filter(c -> !c.system())
				.orElseThrow(() -> ApiException.badRequest("unknown_column",
						"table '" + table.name() + "' has no writable column '" + field.getKey() + "'"));
			names.add(Identifiers.quote(column.name()));
			// Every value is sent as text and converted by Postgres to the column's
			// type. The type name comes from the ColumnType enum, not the request.
			placeholders.add("cast(? as " + column.type().sql() + ")");
			values.add(asText(column, field.getValue()));
		}
		String target = qualified(identity, table);
		String insert = names.isEmpty() ? "insert into " + target + " default values"
				: "insert into " + target + " (" + String.join(", ", names) + ") values ("
						+ String.join(", ", placeholders) + ")";
		String sql = "with inserted as (" + insert + " returning " + columnList(table)
				+ ") select row_to_json(inserted)::text from inserted";
		return run(identity, jdbc -> jdbc.queryForObject(sql, String.class, values.toArray()));
	}

	private TableDef table(TenantIdentity identity, String name) {
		return catalogs.get(identity.project().id())
			.table(name)
			.orElseThrow(() -> ApiException.notFound("no table named '" + name + "'"));
	}

	private String asText(ColumnDef column, Object value) {
		if (value == null) {
			return null;
		}
		if (column.type() == ColumnType.JSONB || value instanceof Map || value instanceof List) {
			return json.writeValueAsString(value);
		}
		return String.valueOf(value);
	}

	private <T> T run(TenantIdentity identity, TenantTransaction.TenantWork<T> work) {
		try {
			return tenant.execute(identity, work);
		}
		catch (DataAccessException e) {
			throw DataErrors.translate(e);
		}
	}

	private static String columnList(TableDef table) {
		return table.columns().stream().map(c -> Identifiers.quote(c.name())).collect(Collectors.joining(", "));
	}

	private static String qualified(TenantIdentity identity, TableDef table) {
		return "\"" + identity.project().schemaName() + "\"." + Identifiers.quote(table.name());
	}

}
