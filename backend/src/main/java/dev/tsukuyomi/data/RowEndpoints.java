package dev.tsukuyomi.data;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.tsukuyomi.tenancy.TenantIdentity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The HTTP side of row operations, shared by the public API and the
 * dashboard's data browser so both behave identically. The two controllers
 * differ only in how they work out WHO is calling.
 */
@Component
class RowEndpoints {

	static final String TOTAL_HEADER = "X-Total-Count";

	private final RowService rows;

	RowEndpoints(RowService rows) {
		this.rows = rows;
	}

	ResponseEntity<String> select(TenantIdentity identity, String table, HttpServletRequest request) {
		// "Prefer: count=exact" asks for the total number of matching rows as well.
		String prefer = request.getHeader("Prefer");
		boolean count = prefer != null && prefer.contains("count=exact");
		RowService.Page page = rows.select(identity, table, parameters(request), count);
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
		if (page.total() != null) {
			response.header(TOTAL_HEADER, String.valueOf(page.total()));
		}
		return response.body(page.json());
	}

	ResponseEntity<String> insert(TenantIdentity identity, String table, Object body) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.contentType(MediaType.APPLICATION_JSON)
			.body(rows.insert(identity, table, body));
	}

	ResponseEntity<String> update(TenantIdentity identity, String table, HttpServletRequest request, Object body) {
		return json(rows.update(identity, table, parameters(request), body));
	}

	ResponseEntity<String> delete(TenantIdentity identity, String table, HttpServletRequest request) {
		return json(rows.delete(identity, table, parameters(request)));
	}

	private static ResponseEntity<String> json(String body) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
	}

	/** The query string as name -> values, already URL-decoded by the servlet container. */
	private static Map<String, List<String>> parameters(HttpServletRequest request) {
		Map<String, List<String>> parameters = new LinkedHashMap<>();
		request.getParameterMap().forEach((name, values) -> parameters.put(name, Arrays.asList(values)));
		return parameters;
	}

}
