package dev.tsukuyomi.data;

import dev.tsukuyomi.tenancy.TenantIdentity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public REST API over user-designed tables. Authenticated by API key
 * (see ApiKeyFilter), which is where the TenantIdentity comes from.
 *
 *   GET    /api/v1/{table}?select=...&col=op.value&order=...&limit=..&offset=..
 *   POST   /api/v1/{table}            body: object or array of objects
 *   PATCH  /api/v1/{table}?col=op.value   body: fields to change
 *   DELETE /api/v1/{table}?col=op.value
 */
@RestController
@RequestMapping("/api/v1/{table}")
class DataController {

	private final RowEndpoints endpoints;

	DataController(RowEndpoints endpoints) {
		this.endpoints = endpoints;
	}

	@GetMapping
	ResponseEntity<String> select(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			HttpServletRequest request) {
		return endpoints.select(identity, table, request);
	}

	@PostMapping
	ResponseEntity<String> insert(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			@RequestBody Object body) {
		return endpoints.insert(identity, table, body);
	}

	@PatchMapping
	ResponseEntity<String> update(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			HttpServletRequest request, @RequestBody Object body) {
		return endpoints.update(identity, table, request, body);
	}

	@DeleteMapping
	ResponseEntity<String> delete(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			HttpServletRequest request) {
		return endpoints.delete(identity, table, request);
	}

}
