package dev.tsukuyomi.schema;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.platform.Account;
import dev.tsukuyomi.platform.ProjectService;
import dev.tsukuyomi.schema.CatalogRepository.MigrationRecord;
import dev.tsukuyomi.tenancy.ProjectRef;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard endpoints for reading the schema and changing it. */
@RestController
@RequestMapping("/platform/v1/projects/{projectId}")
class SchemaController {

	record MigrationRequest(List<SchemaOperation> operations) {
	}

	private final ProjectService projects;

	private final CatalogService catalogs;

	private final MigrationService migrations;

	SchemaController(ProjectService projects, CatalogService catalogs, MigrationService migrations) {
		this.projects = projects;
		this.catalogs = catalogs;
		this.migrations = migrations;
	}

	@GetMapping("/schema")
	Catalog schema(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		return catalogs.get(owned(account, projectId).id());
	}

	@PostMapping("/migrations")
	MigrationService.Result migrate(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@RequestParam(defaultValue = "false") boolean dryRun, @RequestBody MigrationRequest request) {
		return migrations.apply(owned(account, projectId), request.operations(), dryRun);
	}

	@GetMapping("/migrations")
	List<MigrationRecord> history(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		return migrations.history(owned(account, projectId));
	}

	/** Every endpoint starts here: the project must belong to the logged-in account. */
	private ProjectRef owned(Account account, UUID projectId) {
		return projects.get(account, projectId).ref();
	}

}
