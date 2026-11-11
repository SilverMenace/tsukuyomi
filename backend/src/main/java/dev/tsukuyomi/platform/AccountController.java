package dev.tsukuyomi.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform/v1")
class AccountController {

	record CredentialsRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {
	}

	private final AccountService accounts;

	private final SessionCookie cookie;

	AccountController(AccountService accounts, SessionCookie cookie) {
		this.accounts = accounts;
		this.cookie = cookie;
	}

	/** Sign up and log in at once. */
	@PostMapping("/accounts")
	ResponseEntity<Account> signup(@Valid @RequestBody CredentialsRequest request) {
		Account account = accounts.signup(request.email(), request.password());
		String token = accounts.startSession(account);
		return ResponseEntity.status(HttpStatus.CREATED)
			.header(HttpHeaders.SET_COOKIE, cookie.issue(token).toString())
			.body(account);
	}

	@PostMapping("/session")
	ResponseEntity<Void> login(@Valid @RequestBody CredentialsRequest request) {
		String token = accounts.login(request.email(), request.password());
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.issue(token).toString()).build();
	}

	@DeleteMapping("/session")
	ResponseEntity<Void> logout(HttpServletRequest request) {
		cookie.read(request).ifPresent(accounts::logout);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear().toString()).build();
	}

	@GetMapping("/me")
	Account me(@AuthenticationPrincipal Account account) {
		return account;
	}

}
