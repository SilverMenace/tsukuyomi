package dev.tsukuyomi.data;

import java.util.Map;
import java.util.UUID;

import dev.tsukuyomi.platform.Account;
import dev.tsukuyomi.platform.ProjectService;
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

/**
 * The dashboard's data browser. Authenticated by the owner's session, then it
 * goes through exactly the same RowService as the public API, acting as the
 * project's service role. There is no second, privileged path to tenant data.
 */
@RestController
@RequestMapping("/platform/v1/projects/{projectId}/tables/{table}/rows")
class DataBrowserController {

	private final ProjectService projects;

	private final RowService rows;

	DataBrowserController(ProjectService projects, RowService rows) {
		this.projects = projects;
		this.rows = rows;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	String list(@AuthenticationPrincipal Account account, @PathVariable UUID projectId, @PathVariable String table,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
		return rows.list(identity(account, projectId), table, limit, offset);
	}

	@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> insert(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String table, @RequestBody Map<String, Object> body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(rows.insert(identity(account, projectId), table, body));
	}

	private TenantIdentity identity(Account account, UUID projectId) {
		return TenantIdentity.service(projects.get(account, projectId).ref());
	}

}
