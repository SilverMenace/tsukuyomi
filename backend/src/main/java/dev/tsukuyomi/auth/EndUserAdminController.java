package dev.tsukuyomi.auth;

import java.util.List;
import java.util.UUID;

import dev.tsukuyomi.auth.EndUserRepository.EndUser;
import dev.tsukuyomi.platform.Account;
import dev.tsukuyomi.platform.ProjectService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard: the list of a project's end users (most recent 200). */
@RestController
class EndUserAdminController {

	private final ProjectService projects;

	private final AuthService auth;

	EndUserAdminController(ProjectService projects, AuthService auth) {
		this.projects = projects;
		this.auth = auth;
	}

	@GetMapping("/platform/v1/projects/{projectId}/users")
	List<EndUser> list(@AuthenticationPrincipal Account account, @PathVariable UUID projectId) {
		return auth.list(projects.get(account, projectId).ref());
	}

}
