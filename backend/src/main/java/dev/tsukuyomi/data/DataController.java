package dev.tsukuyomi.data;

import java.util.Map;

import dev.tsukuyomi.tenancy.TenantIdentity;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The public REST API over user-designed tables. Authenticated by API key. */
@RestController
@RequestMapping("/api/v1")
class DataController {

	private final RowService rows;

	DataController(RowService rows) {
		this.rows = rows;
	}

	@GetMapping(value = "/{table}", produces = MediaType.APPLICATION_JSON_VALUE)
	String list(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
		return rows.list(identity, table, limit, offset);
	}

	@PostMapping(value = "/{table}", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> insert(@AuthenticationPrincipal TenantIdentity identity, @PathVariable String table,
			@RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(rows.insert(identity, table, body));
	}

}
