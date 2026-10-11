package com.wise.escrow.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * HS256 shared secret — same scheme as the Python service so tokens are
     * interoperable across both backends during migration. The property is
     * mandatory outside local dev.
     */
    @Bean
    SecretKey jwtSecretKey(@Value("${wise.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                "wise.jwt.secret must be >= 32 bytes for HS256"
            );
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * SPA cross-origin access. Origins come from WISE_CORS_ORIGINS
     * (comma-separated); the default covers the local Vite dev server.
     */
    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
        @Value("${wise.cors.allowed-origins:http://localhost:5173}") String origins
    ) {
        var config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(java.util.List.of(origins.split(",")));
        config.setAllowedMethods(
            java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        );
        config.setAllowedHeaders(
            java.util.List.of("Authorization", "Content-Type", "X-Idempotency-Key")
        );
        var source =
            new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth ->
                auth
                    .requestMatchers(
                        "/api/v1/auth/**",
                        "/webhooks/**",
                        "/actuator/health",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                    )
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }

    /** Issues HS256 tokens carrying sub (user id) + entity_id (tenant). */
    @org.springframework.stereotype.Component
    public static class JwtIssuer {

        private final JwtEncoder encoder;
        private final int expireMinutes;

        /** Creates a new JwtIssuer. */
        public JwtIssuer(
            JwtEncoder encoder,
            @Value("${wise.jwt.expire-minutes:120}") int expireMinutes
        ) {
            this.encoder = encoder;
            this.expireMinutes = expireMinutes;
        }

        /** Whether sue. */
        public String issue(UUID userId, UUID entityId) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("entity_id", entityId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(expireMinutes, ChronoUnit.MINUTES))
                .build();
            return encoder
                .encode(
                    JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims
                    )
                )
                .getTokenValue();
        }
    }
}
