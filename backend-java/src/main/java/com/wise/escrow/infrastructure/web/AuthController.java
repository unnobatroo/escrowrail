package com.wise.escrow.infrastructure.web;

import com.wise.escrow.infrastructure.web.dto.profile.EntitySummaryResponse;
import com.wise.escrow.infrastructure.web.dto.auth.LoginUserRequest;
import com.wise.escrow.infrastructure.web.dto.auth.PendingVerificationResponse;
import com.wise.escrow.infrastructure.web.dto.auth.RegisterUserRequest;
import com.wise.escrow.infrastructure.web.dto.auth.RegisterResponse;
import com.wise.escrow.infrastructure.web.dto.auth.ResendVerificationRequest;
import com.wise.escrow.infrastructure.web.dto.auth.AuthTokenResponse;
import com.wise.escrow.infrastructure.web.dto.profile.UserProfileResponse;
import com.wise.escrow.infrastructure.web.dto.auth.VerifyEmailRequest;
import com.wise.escrow.application.service.AuthService;
import com.wise.escrow.application.service.AuthService.Registration;
import com.wise.escrow.domain.model.AppUser;
import com.wise.escrow.domain.model.Organization;
import com.wise.escrow.domain.repository.AppUserRepository;
import com.wise.escrow.domain.repository.OrganizationRepository;
import com.wise.escrow.infrastructure.security.SecurityConfig.JwtIssuer;
import com.wise.escrow.infrastructure.security.TenantPrincipal;
import com.wise.escrow.domain.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtIssuer jwt;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;

    /** Creates a new AuthController. */
    public AuthController(
            AuthService auth,
            JwtIssuer jwt,
            AppUserRepository users,
            OrganizationRepository organizations) {
        this.auth = auth;
        this.jwt = jwt;
        this.users = users;
        this.organizations = organizations;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    /** Registers an unverified account — a JWT is only issued on verification. */
    public PendingVerificationResponse register(@Valid @RequestBody RegisterUserRequest body) {
        Registration reg = auth.register(
                body.email(), body.password(), body.legalName(), body.uboTaxId(), body.country());
        return new PendingVerificationResponse("PENDING_VERIFICATION", reg.user().email());
    }

    @PostMapping("/verify-email")
    /** Consumes the emailed token; on success the user is logged in immediately. */
    public RegisterResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest body) {
        AppUser user = auth.verifyEmail(body.token());
        Organization org = organizations
                .findById(user.entityId())
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        return new RegisterResponse(
                jwt.issue(user.id(), user.entityId()), toView(user, org));
    }

    @PostMapping("/resend-verification")
    /** Re-issues the link; always 200 so addresses cannot be enumerated. */
    public java.util.Map<String, String> resend(
            @Valid @RequestBody ResendVerificationRequest body) {
        auth.resendVerification(body.email());
        return java.util.Map.of("status", "ok");
    }

    @PostMapping("/login")
    /** Authenticates credentials and issues a bearer JWT. */
    public AuthTokenResponse login(@Valid @RequestBody LoginUserRequest body) {
        AppUser user = auth.authenticate(body.email(), body.password());
        return new AuthTokenResponse(jwt.issue(user.id(), user.entityId()), "bearer");
    }

    @GetMapping("/me")
    /** Returns the authenticated caller's profile. */
    public UserProfileResponse me() {
        AppUser user = users
                .findById(TenantPrincipal.userId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        Organization org = organizations
                .findById(user.entityId())
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        return toView(user, org);
    }

    private static UserProfileResponse toView(AppUser user, Organization org) {
        return new UserProfileResponse(
                user.id(),
                user.email(),
                user.active(),
                user.emailVerified(),
                user.status().name(),
                user.onboardingCompleted(),
                new EntitySummaryResponse(
                        org.id(), org.legalName(), org.uboTaxId().value(), org.country()),
                user.createdAt());
    }
}
