package com.wise.escrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end lifecycle over HTTP: auth, UBO guardrail, fund, webhook
 * settle (0.8% split), asymmetric cancel, and tenant isolation.
 */
@AutoConfigureMockMvc
class EscrowApiIntegrationTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private String tokenOf(String email, String org, String ubo, String tag)
        throws Exception {
        String body = mapper.writeValueAsString(
            java.util.Map.of(
                "email",
                email + "-" + tag + "@t.dev",
                "password",
                "pw123456",
                "legal_name",
                org,
                "ubo_tax_id",
                ubo,
                "country",
                "US"
            )
        );
        String fullEmail = email + "-" + tag + "@t.dev";
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
        // Complete the emailed verification via the dev-token backdoor.
        MvcResult tok = mvc
            .perform(get("/dev/verification-token").param("email", fullEmail))
            .andExpect(status().isOk())
            .andReturn();
        String rawToken = JsonPath.read(
            tok.getResponse().getContentAsString(),
            "$.token"
        );
        MvcResult res = mvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + rawToken + "\"}")
            )
            .andExpect(status().isOk())
            .andReturn();
        return JsonPath.read(
            res.getResponse().getContentAsString(),
            "$.access_token"
        );
    }

    private String createContract(String token, String sellerEmail, String key)
        throws Exception {
        MvcResult res = mvc
            .perform(
                post("/api/v1/escrow")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"counterparty_email":"%s","target_amount":"1000.00",
                         "tracking_type":"GITHUB_PR","tracking_key":"%s"}
                        """.formatted(sellerEmail, key)
                    )
            )
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void fullLifecycleSettlesWithCorrectSplit() throws Exception {
        String tag = "lc1";
        String buyerToken = tokenOf("buyer", "Buyer LC", "UB-LC-B", tag);
        String sellerToken = tokenOf("seller", "Seller LC", "UB-LC-S", tag);

        String contractId = createContract(
            buyerToken,
            "seller-lc1@t.dev",
            "pr-lc1"
        );

        // Quote → fund
        MvcResult quoteRes = mvc
            .perform(
                get("/api/v1/escrow/quote")
                    .param("target_amount", "1000.00")
                    .header("Authorization", "Bearer " + buyerToken)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.wise_fee").exists())
            .andExpect(jsonPath("$.customer_savings").exists())
            .andReturn();
        String total = JsonPath.read(
            quoteRes.getResponse().getContentAsString(),
            "$.total_deposit"
        ).toString();

        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"" + total + "\"}")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FUNDED"))
            .andExpect(jsonPath("$.escrowed_amount").value(1000.00));

        // GitHub merge webhook settles (HMAC-signed like a real delivery)
        byte[] ghBody = """
        {"action":"closed","pull_request":{"merged":true,"html_url":"pr-lc1"}}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(
            post("/webhooks/github")
                .header("X-Hub-Signature-256", ghSignature(ghBody))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ghBody)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("settled"));

        // The contract audit trail shows the 8.00 settlement fee leg
        // landing on the Wise fee account, not just the seller payout.
        mvc.perform(
            get("/api/v1/escrow/" + contractId + "/audit").header(
                "Authorization",
                "Bearer " + buyerToken
            )
        )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                    "$[?(@.description=='Settlement fee (0.8%%)' && @.amount==8.00)]"
                ).exists()
            );

        // Seller EUR account holds 10000 sandbox + 992.00 payout.
        MvcResult sellerAccounts = mvc
            .perform(
                get("/api/v1/accounts").header(
                    "Authorization",
                    "Bearer " + sellerToken
                )
            )
            .andExpect(status().isOk())
            .andReturn();
        JsonNode accounts = mapper.readTree(
            sellerAccounts.getResponse().getContentAsString()
        );
        int eurCount = 0;
        for (JsonNode a : accounts) {
            if ("EUR".equals(a.get("currency").asText())) {
                eurCount++;
                org.assertj.core.api.Assertions.assertThat(
                    new java.math.BigDecimal(a.get("balance").asText())
                ).isEqualByComparingTo("10992.00");
            }
        }
        org.assertj.core.api.Assertions.assertThat(eurCount).isEqualTo(1);
    }

    @Test
    void uboCollisionRejectedWith403() throws Exception {
        String tag = "ubo1";
        String buyerToken = tokenOf("buyer", "Buyer", "SAME-UBO", tag);
        tokenOf("alter", "Buyer Shell", "SAME-UBO", tag);

        mvc.perform(
            post("/api/v1/escrow")
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"counterparty_email":"alter-ubo1@t.dev","target_amount":"100",
                     "tracking_type":"GITHUB_PR","tracking_key":"k"}
                    """
                )
        )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CIRCULAR_OWNERSHIP_DETECTED"));
    }

    @Test
    void strangerCannotReadOrFund() throws Exception {
        String tag = "ten1";
        String buyerToken = tokenOf("buyer", "B", "UB-T1-B", tag);
        String sellerToken = tokenOf("seller", "S", "UB-T1-S", tag);
        String strangerToken = tokenOf("stranger", "X", "UB-T1-X", tag);
        String contractId = createContract(
            buyerToken,
            "seller-ten1@t.dev",
            "pr-ten1"
        );

        mvc.perform(
            get("/api/v1/escrow/" + contractId).header(
                "Authorization",
                "Bearer " + strangerToken
            )
        ).andExpect(status().isNotFound());
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/cancel").header(
                "Authorization",
                "Bearer " + strangerToken
            )
        ).andExpect(status().isNotFound());
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + strangerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"1100.00\"}")
        ).andExpect(status().isNotFound());
        // Seller is a party but not the buyer → 403 on writes.
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + sellerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"1100.00\"}")
        ).andExpect(status().isForbidden());
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/cancel").header(
                "Authorization",
                "Bearer " + sellerToken
            )
        ).andExpect(status().isForbidden());
    }

    @Test
    void fundedCancellationLiquidatesToUsd() throws Exception {
        String tag = "cx1";
        String buyerToken = tokenOf("buyer", "B", "UB-CX-B", tag);
        tokenOf("seller", "S", "UB-CX-S", tag);
        String contractId = createContract(
            buyerToken,
            "seller-cx1@t.dev",
            "pr-cx1"
        );

        MvcResult quoteRes = mvc
            .perform(
                get("/api/v1/escrow/quote")
                    .param("target_amount", "1000.00")
                    .header("Authorization", "Bearer " + buyerToken)
            )
            .andReturn();
        String total = JsonPath.read(
            quoteRes.getResponse().getContentAsString(),
            "$.total_deposit"
        ).toString();
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"" + total + "\"}")
        ).andExpect(status().isOk());

        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/cancel").header(
                "Authorization",
                "Bearer " + buyerToken
            )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Audit trail shows the liquidation legs.
        mvc.perform(
            get("/api/v1/escrow/" + contractId + "/audit").header(
                "Authorization",
                "Bearer " + buyerToken
            )
        )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$[?(@.description=='Vault liquidation')]").exists()
            );
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mvc.perform(get("/api/v1/escrow")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts")).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void unsignedOrForgedWebhookRejected() throws Exception {
        byte[] body = """
        {"action":"closed","pull_request":{"merged":true,"html_url":"x"}}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // No signature at all.
        mvc.perform(
            post("/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest());
        // Forged signature.
        mvc.perform(
            post("/webhooks/github")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest());
        // Logistics without the shared secret.
        mvc.perform(
            post("/webhooks/logistics")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"carrier\":\"DHL\",\"status\":\"DELIVERED\",\"tracking_key\":\"k\"}"
                )
        ).andExpect(status().isBadRequest());
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

    @Test
    void inviteReturns202ForUnknownCounterparty() throws Exception {
        String buyerToken = tokenOf("buyer", "B", "UB-IN-B", "in1");
        mvc.perform(
            post("/api/v1/escrow")
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"counterparty_email":"ghost@nowhere.dev","target_amount":"500",
                     "tracking_type":"SHIPPING_CARRIER","tracking_key":"DHL-1"}
                    """
                )
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("invited"));
    }
}
