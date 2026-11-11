package dev.tsukuyomi.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform/v1/projects")
class ProjectController {

	record CreateRequest(@NotBlank @Size(max = 50) String name) {
	}

	private final ProjectService projects;

	ProjectController(ProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	List<Project> list(@AuthenticationPrincipal Account account) {
		return projects.list(account);
	}

	@PostMapping
	ResponseEntity<ProjectService.Created> create(@AuthenticationPrincipal Account account,
			@Valid @RequestBody CreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(account, request.name()));
	}

	@GetMapping("/{projectId}")
	Project get(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		return projects.get(account, projectId);
	}

	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		projects.delete(account, projectId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{projectId}/keys")
	List<ApiKeyInfo> keys(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		return projects.keys(account, projectId);
	}

	@PostMapping("/{projectId}/keys/{kind}/rotate")
	Map<String, String> rotate(@AuthenticationPrincipal Account account, @PathVariable UUID projectId,
			@PathVariable String kind) {
		return Map.of("kind", kind, "key", projects.rotateKey(account, projectId, kind));
	}

}
