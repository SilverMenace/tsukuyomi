package dev.tsukuyomi.data;

import java.util.UUID;

import dev.tsukuyomi.platform.Account;
import dev.tsukuyomi.platform.ProjectService;
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
 * The dashboard's data browser. Authenticated by the owner's session, then it
 * goes through exactly the same code as the public API, acting as the
 * project's service role. There is no second, privileged path to tenant data.
 * Same query syntax as /api/v1.
 */
@RestController
@RequestMapping("/platform/v1/projects/{projectId}/tables/{table}/rows")
class DataBrowserController {

	private final ProjectService projects;

	private final RowEndpoints endpoints;

	DataBrowserController(ProjectService projects, RowEndpoints endpoints) {
		this.projects = projects;
		this.endpoints = endpoints;
	}

	@GetMapping
	ResponseEntity<String> select(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String table, HttpServletRequest request) {
		return endpoints.select(identity(account, projectId), table, request);
	}

	@PostMapping
	ResponseEntity<String> insert(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String table, @RequestBody Object body) {
		return endpoints.insert(identity(account, projectId), table, body);
	}

	@PatchMapping
	ResponseEntity<String> update(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String table, HttpServletRequest request, @RequestBody Object body) {
		return endpoints.update(identity(account, projectId), table, request, body);
	}

	@DeleteMapping
	ResponseEntity<String> delete(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String table, HttpServletRequest request) {
		return endpoints.delete(identity(account, projectId), table, request);
	}

	private TenantIdentity identity(Account account, UUID projectId) {
		return TenantIdentity.service(projects.get(account, projectId).ref());
	}

}
