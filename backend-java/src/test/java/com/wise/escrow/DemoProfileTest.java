package com.escrowrail.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Demo profile contract: seed tenants are provisioned and can run the
 * full buyer→seller escrow lifecycle, the demo gateway is available, and
 * the dev/test token backdoor is still unmapped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "demo", inheritProfiles = false)
@TestPropertySource(
    properties = {
        // Base yml requires these; the real datasource comes from the
        // Testcontainers @ServiceConnection.
        "DATABASE_USER=unused",
        "DATABASE_PASSWORD=unused",
        "JWT_SECRET=test-secret-test-secret-test-secret-32",
        "ESCROWRAIL_GITHUB_WEBHOOK_SECRET=test-gh-secret",
        "ESCROWRAIL_LOGISTICS_WEBHOOK_SECRET=test-logi-secret",
        "DEMO_BUYER_PASSWORD=EscrowDemo123!",
        "DEMO_SELLER_PASSWORD=EscrowDemo123!",
    }
)
class DemoProfileTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private String login(String email) throws Exception {
        MvcResult res = mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" +
                            email +
                            "\",\"password\":\"EscrowDemo123!\"}"
                    )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isString())
            .andReturn();
        return JsonPath.read(
            res.getResponse().getContentAsString(),
            "$.access_token"
        );
    }

    @Test
    void seededTenantsRunTheP2PLifecycle() throws Exception {
        String buyer = login("buyer@demo.com");
        String seller = login("seller@demo.com");
        assertThat(buyer).isNotBlank();
        assertThat(seller).isNotBlank();

        // Buyer creates the contract against the seeded seller.
        String trackingKey = "demo-pr-" + System.nanoTime();
        MvcResult created = mvc
            .perform(
                post("/api/v1/escrow")
                    .header("Authorization", "Bearer " + buyer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"counterparty_email\":\"seller@demo.com\"," +
                            "\"target_amount\":\"500.00\"," +
                            "\"tracking_type\":\"GITHUB_PR\"," +
                            "\"tracking_key\":\"" +
                            trackingKey +
                            "\"}"
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("INITIATED"))
            .andReturn();
        String contractId = JsonPath.read(
            created.getResponse().getContentAsString(),
            "$.id"
        );

        MvcResult quoteRes = mvc
            .perform(
                get("/api/v1/escrow/quote")
                    .param("target_amount", "500.00")
                    .header("Authorization", "Bearer " + buyer)
            )
            .andExpect(status().isOk())
            .andReturn();
        String total = JsonPath.read(
            quoteRes.getResponse().getContentAsString(),
            "$.total_deposit"
        ).toString();

        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"" + total + "\"}")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FUNDED"));

        // Seller sees the same contract as FUNDED — tenant-scoped view.
        mvc.perform(
            get("/api/v1/escrow/" + contractId).header(
                "Authorization",
                "Bearer " + seller
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FUNDED"));

        // A correctly signed GitHub merge webhook settles it.
        byte[] ghBody = (
            "{\"action\":\"closed\",\"pull_request\":{\"merged\":true," +
            "\"html_url\":\"" +
            trackingKey +
            "\"}}"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(
            post("/webhooks/github")
                .header("X-Hub-Signature-256", ghSignature(ghBody))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ghBody)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("settled"));
    }

    @Test
    void demoGatewayIsOnAndTokenBackdoorIsOff() throws Exception {
        String buyer = login("buyer@demo.com");
        mvc.perform(
            get("/api/v1/demo/config").header(
                "Authorization",
                "Bearer " + buyer
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
        // /dev/** is a dev/test artifact — absent under the demo profile.
        mvc.perform(
            get("/dev/verification-token").param("email", "buyer@demo.com")
        ).andExpect(status().isNotFound());
    }

    private static String ghSignature(byte[] body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
            new javax.crypto.spec.SecretKeySpec(
                "test-gh-secret".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                ),
                "HmacSHA256"
            )
        );
        return (
            "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body))
        );
    }
}
