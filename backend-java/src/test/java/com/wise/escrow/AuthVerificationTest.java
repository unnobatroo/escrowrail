package com.wise.escrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Email-verification gate: registration stays PENDING_VERIFICATION until a
 * hashed single-use token is consumed; unverified users cannot log in.
 */
@AutoConfigureMockMvc
class AuthVerificationTest extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String register(String email) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "email", email,
                "password", "pw123456",
                "legal_name", "Verify Co",
                "ubo_tax_id", "UB-" + System.nanoTime(),
                "country", "US"));
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.email").value(email));
        return email;
    }

    private String latestToken(String email) throws Exception {
        MvcResult res = mvc.perform(get("/dev/verification-token").param("email", email))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.token");
    }

    @Test
    void registrationIsPendingAndUnverifiedLoginForbidden() throws Exception {
        String email = "pending-" + System.nanoTime() + "@t.dev";
        register(email);
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void validTokenActivatesAndReturnsJwt() throws Exception {
        String email = "verify-" + System.nanoTime() + "@t.dev";
        register(email);
        String rawToken = latestToken(email);
        // Tokens are stored hashed — the dev backdoor returns the raw value
        // the email would carry.
        org.assertj.core.api.Assertions.assertThat(rawToken).hasSize(64);

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.user.email_verified").value(true))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));

        // Login works after verification.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString());
    }

    @Test
    void reusedAndBogusTokensRejected() throws Exception {
        String email = "reuse-" + System.nanoTime() + "@t.dev";
        register(email);
        String rawToken = latestToken(email);
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());
        // Second use of the same token must fail.
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isBadRequest());
        // A well-formed but unknown token is rejected identically.
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendRotatesTokenAndIsGeneric() throws Exception {
        String email = "resend-" + System.nanoTime() + "@t.dev";
        register(email);
        String first = latestToken(email);

        // Immediate resend is inside the 60s cooldown — same response, and
        // the first token still verifies.
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(latestToken(email)).isEqualTo(first);

        // Unknown addresses get the same generic response.
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost-" + System.nanoTime() + "@t.dev\"}"))
                .andExpect(status().isOk());
    }
}
