package com.escrowrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.escrowrail.config.SeedRunner;
import com.escrowrail.infrastructure.web.DemoToolsController;
import com.escrowrail.infrastructure.web.DevTokenController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Prod profile audit: every demo loophole (seed, top-up, webhook sim, token backdoor) unmapped, webhooks demand real signatures, unverified accounts stay off the dashboard. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "prod", inheritProfiles = false)
@TestPropertySource(properties = {
    // Required placeholders from application-prod.yml; the actual
    // datasource comes from the Testcontainers @ServiceConnection.
    "SPRING_DATASOURCE_URL=jdbc:postgresql://placeholder.invalid/escrowrail",
    "SPRING_DATASOURCE_USERNAME=unused",
    "SPRING_DATASOURCE_PASSWORD=unused",
    "JWT_SECRET=test-secret-test-secret-test-secret-32",
    "ESCROWRAIL_GITHUB_WEBHOOK_SECRET=test-gh-secret",
    "ESCROWRAIL_LOGISTICS_WEBHOOK_SECRET=test-logi-secret",
    "ESCROWRAIL_FIELD_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
})
class ProductionSecurityProfileTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ApplicationContext ctx;

    @Test
    void demoToolingIsAbsentFromTheContext() {
        assertThat(ctx.getBeanNamesForType(DemoToolsController.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(DevTokenController.class)).isEmpty();
        // Seeding must never run automatically in production.
        assertThat(ctx.getBeanNamesForType(SeedRunner.class)).isEmpty();
    }

    @Test
    void demoRoutesAreUnmapped() throws Exception {
        // Faucet, gateway config, and the webhook simulator: 404 even with
        // no auth — the mapping does not exist at all.
        mvc.perform(
            post("/api/v1/demo/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"1000\"}")
        ).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo/config")).andExpect(status().isNotFound());
        mvc.perform(
            post("/api/v1/demo/simulate-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contract_id\":\"" + java.util.UUID.randomUUID() + "\"}")
        ).andExpect(status().isNotFound());
        // The verification-token backdoor exists only in dev/test.
        mvc.perform(
            get("/dev/verification-token").param("email", "x@y.dev")
        ).andExpect(status().isNotFound());
    }

    @Test
    void unsignedWebhooksAreRejected() throws Exception {
        byte[] body = """
        {"action":"closed","pull_request":{"merged":true,"html_url":"x"}}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(
            post("/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().is4xxClientError());
        mvc.perform(
            post("/webhooks/github")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().is4xxClientError());
        mvc.perform(
            post("/webhooks/logistics")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"carrier\":\"DHL\",\"status\":\"DELIVERED\",\"tracking_key\":\"k\"}"
                )
        ).andExpect(status().is4xxClientError());
    }

    @Test
    void unverifiedLoginIsForbidden() throws Exception {
        String email = "prod-" + System.nanoTime() + "@t.dev";
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of(
                            "email", email,
                            "password", "pw123456",
                            "legal_name", "Prod Audit Co",
                            "ubo_tax_id", "UB-" + System.nanoTime(),
                            "country", "US"
                        )
                    )
                )
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"" + email + "\",\"password\":\"pw123456\"}"
                )
        )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }
}
