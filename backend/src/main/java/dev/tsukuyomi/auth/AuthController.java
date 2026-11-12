package dev.tsukuyomi.auth;

import dev.tsukuyomi.auth.AuthService.TokenResponse;
import dev.tsukuyomi.auth.AuthService.UserInfo;
import dev.tsukuyomi.common.ApiException;
import dev.tsukuyomi.tenancy.TenantIdentity;
import dev.tsukuyomi.tenancy.TenantRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints for the end users of a project. The apikey header says which
 * project; ApiKeyFilter has already turned it into the TenantIdentity.
 */
@RestController
@RequestMapping("/auth/v1")
class AuthController {

	record CredentialsRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {
	}

	record RefreshRequest(@NotBlank @Size(max = 200) String refresh_token) {
	}

	private final AuthService auth;

	AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/signup")
	ResponseEntity<TokenResponse> signup(@AuthenticationPrincipal TenantIdentity identity,
			@Valid @RequestBody CredentialsRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(auth.signup(identity.project(), request.email(), request.password()));
	}

	@PostMapping("/login")
	TokenResponse login(@AuthenticationPrincipal TenantIdentity identity, @Valid @RequestBody CredentialsRequest request) {
		return auth.login(identity.project(), request.email(), request.password());
	}

	@PostMapping("/refresh")
	TokenResponse refresh(@AuthenticationPrincipal TenantIdentity identity, @Valid @RequestBody RefreshRequest request) {
		return auth.refresh(identity.project(), request.refresh_token());
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@AuthenticationPrincipal TenantIdentity identity,
			@Valid @RequestBody RefreshRequest request) {
		auth.logout(identity.project(), request.refresh_token());
		return ResponseEntity.noContent().build();
	}

	/** Who the access token belongs to. */
	@GetMapping("/me")
	UserInfo me(@AuthenticationPrincipal TenantIdentity identity) {
		if (identity.role() != TenantRole.USER) {
			throw ApiException.unauthorized("send an access token in the Authorization header");
		}
		return auth.find(identity.project(), identity.userId())
			.orElseThrow(() -> ApiException.unauthorized("this user no longer exists"));
	}

}
