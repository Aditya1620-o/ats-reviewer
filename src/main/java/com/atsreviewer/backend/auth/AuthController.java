package com.atsreviewer.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atsreviewer.backend.auth.AuthDtos.LoginRequest;
import com.atsreviewer.backend.auth.AuthDtos.RefreshRequest;
import com.atsreviewer.backend.auth.AuthDtos.SignupRequest;
import com.atsreviewer.backend.auth.AuthDtos.TokenResponse;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request.refreshToken());
		return ResponseEntity.noContent().build();
	}
}