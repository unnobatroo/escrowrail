package com.escrowrail;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Password reset flow: single-use hashed tokens, anti-enumeration 200s,
 * invalidation on re-issue, and old-password rejection after reset.
 */
@AutoConfigureMockMvc
class PasswordResetTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    /** Registers and verifies a user; returns the email. */
    private String registerVerified(String email) throws Exception {
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of(
                        "email", email,
                        "password", "pw123456",
                        "legal_name", "Reset Co",
                        "ubo_tax_id", "UB-" + System.nanoTime(),
                        "country", "US"))))
            .andExpect(status().isCreated());
        MvcResult tok = mvc.perform(
            get("/dev/verification-token").param("email", email))
            .andReturn();
        String token = JsonPath.read(tok.getResponse().getContentAsString(), "$.token");
        mvc.perform(
            post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("token", token))))
            .andExpect(status().isOk());
        return email;
    }

    private String latestResetToken(String email) throws Exception {
        MvcResult res = mvc.perform(
            get("/dev/reset-token").param("email", email))
            .andExpect(status().isOk())
            .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.token");
    }

    private void requestReset(String email) throws Exception {
        mvc.perform(
            post("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("email", email))))
            .andExpect(status().isOk());
    }

    private void loginExpect(String email, String password, int status) throws Exception {
        mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of("email", email, "password", password))))
            .andExpect(status().is(status));
    }

    @Test
    void resetSwapsPasswordAndConsumesToken() throws Exception {
        String email = "reset-" + System.nanoTime() + "@test.dev";
        registerVerified(email);
        requestReset(email);
        String token = latestResetToken(email);

        mvc.perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of("token", token, "new_password", "NewPass456!"))))
            .andExpect(status().isOk());

        loginExpect(email, "NewPass456!", 200);
        loginExpect(email, "pw123456", 401);

        // Single-use: replaying the same token must fail.
        mvc.perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of("token", token, "new_password", "Another789!"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAndUnverifiedEmailsStaySilent() throws Exception {
        // Unknown address: 200 either way, no token issued.
        String ghost = "ghost-" + System.nanoTime() + "@test.dev";
        requestReset(ghost);
        mvc.perform(get("/dev/reset-token").param("email", ghost))
            .andExpect(status().isNotFound());

        // Registered but unverified: also silent — no reset token.
        String pending = "pending-" + System.nanoTime() + "@test.dev";
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of(
                        "email", pending,
                        "password", "pw123456",
                        "legal_name", "Pending Co",
                        "ubo_tax_id", "UB-" + System.nanoTime(),
                        "country", "US"))))
            .andExpect(status().isCreated());
        requestReset(pending);
        mvc.perform(get("/dev/reset-token").param("email", pending))
            .andExpect(status().isNotFound());
    }

    @Test
    void reissueInvalidatesTheEarlierLink() throws Exception {
        String email = "reissue-" + System.nanoTime() + "@test.dev";
        registerVerified(email);
        requestReset(email);
        String first = latestResetToken(email);

        // Backdate the token past the 60s cooldown so the second request
        // actually issues a replacement (JdbcTemplate, no sleeping).
        jdbc.update(
            "UPDATE password_reset_tokens SET created_at = created_at - interval '5 minutes' " +
            "WHERE user_id = (SELECT id FROM app_users WHERE email = ?)", email);
        requestReset(email);
        String second = latestResetToken(email);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);

        // The superseded link is dead; the replacement works.
        mvc.perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of("token", first, "new_password", "DeadLink1!"))))
            .andExpect(status().isBadRequest());
        mvc.perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of("token", second, "new_password", "Reset777!"))))
            .andExpect(status().isOk());
    }
}
