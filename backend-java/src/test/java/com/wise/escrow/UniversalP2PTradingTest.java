package com.escrowrail.escrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Universal P2P trading: roles are per-contract, not per-tenant. Two random
 * registered entities trade in both directions — Alpha buys from Beta, then
 * Beta buys from Alpha — and a trade invited to an unregistered address
 * attaches to that entity automatically on signup.
 */
@AutoConfigureMockMvc
class UniversalP2PTradingTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    /** Registers, verifies, and onboards a USD balance sized for the trades. */
    private String onboard(String email, String org, String ubo, String usd)
        throws Exception {
        String body = mapper.writeValueAsString(
            java.util.Map.of(
                "email", email,
                "password", "pw123456",
                "legal_name", org,
                "ubo_tax_id", ubo,
                "country", "US"
            )
        );
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
        MvcResult tok = mvc
            .perform(get("/dev/verification-token").param("email", email))
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
        String jwt = JsonPath.read(
            res.getResponse().getContentAsString(),
            "$.access_token"
        );
        mvc.perform(
            put("/api/v1/profile")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initial_deposits\":{\"USD\":\"" + usd + "\"}}")
        ).andExpect(status().isOk());
        return jwt;
    }

    private String createTrade(
        String buyerToken, String sellerEmail,
        String targetAmount, String key
    ) throws Exception {
        MvcResult res = mvc
            .perform(
                post("/api/v1/escrow")
                    .header("Authorization", "Bearer " + buyerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"counterparty_email":"%s","target_amount":"%s",
                         "tracking_type":"GITHUB_PR","tracking_key":"%s"}
                        """.formatted(sellerEmail, targetAmount, key)
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("INITIATED"))
            .andExpect(jsonPath("$.role").value("buyer"))
            .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    /** Quotes the exact deposit and funds the contract as the given user. */
    private void fund(String token, String contractId, String targetAmount)
        throws Exception {
        MvcResult quote = mvc
            .perform(
                get("/api/v1/escrow/quote")
                    .param("target_amount", targetAmount)
                    .header("Authorization", "Bearer " + token)
            )
            .andExpect(status().isOk())
            .andReturn();
        String total = JsonPath.read(
            quote.getResponse().getContentAsString(),
            "$.total_deposit"
        ).toString();
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"" + total + "\"}")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FUNDED"));
    }

    private void settle(String trackingKey) throws Exception {
        byte[] body = """
        {"action":"closed","pull_request":{"merged":true,"html_url":"%s"}}
        """.formatted(trackingKey)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(
            post("/webhooks/github")
                .header("X-Hub-Signature-256", signature(body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("settled"));
    }

    /**
     * What the seller is owed on settlement: the vaulted escrowed amount less
     * the 0.8% settlement fee, rounded HALF_EVEN like the service computes.
     * The escrowed amount is read back from the contract because FX
     * conversion can land a cent over the round target.
     */
    private java.math.BigDecimal expectedPayout(String token, String contractId)
        throws Exception {
        MvcResult res = mvc
            .perform(
                get("/api/v1/escrow/" + contractId)
                    .header("Authorization", "Bearer " + token)
            )
            .andExpect(status().isOk())
            .andReturn();
        String escrowed = JsonPath.read(
            res.getResponse().getContentAsString(),
            "$.escrowed_amount"
        ).toString();
        java.math.BigDecimal e = new java.math.BigDecimal(escrowed);
        return e.subtract(
            e.multiply(new java.math.BigDecimal("0.008"))
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
        );
    }

    private java.math.BigDecimal balance(String token, String currency)
        throws Exception {
        MvcResult res = mvc
            .perform(
                get("/api/v1/accounts").header(
                    "Authorization",
                    "Bearer " + token
                )
            )
            .andExpect(status().isOk())
            .andReturn();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode a : mapper.readTree(res.getResponse().getContentAsString())) {
            if (currency.equals(a.get("currency").asText())) {
                total = total.add(
                    new java.math.BigDecimal(a.get("balance").asText())
                );
            }
        }
        return total;
    }

    @Test
    void anyTwoUsersTradeInBothDirections() throws Exception {
        String tag = "" + System.nanoTime();
        String alphaEmail = "alpha-" + tag + "@t.dev";
        String betaEmail = "beta-" + tag + "@t.dev";
        String alpha = onboard(alphaEmail, "Company Alpha " + tag, "UB-A-" + tag, "10000.00");
        String beta = onboard(betaEmail, "Company Beta " + tag, "UB-B-" + tag, "5000.00");

        // Direction 1: Alpha buys from Beta for 5,000 EUR and funds it.
        String first = createTrade(alpha, betaEmail, "5000.00", "pr-a-" + tag);
        // Beta sees the same contract in the seller role.
        mvc.perform(
            get("/api/v1/escrow/" + first)
                .header("Authorization", "Bearer " + beta)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("seller"))
            .andExpect(jsonPath("$.status").value("INITIATED"));
        // The seller must not fund a contract they do not buy side of.
        mvc.perform(
            post("/api/v1/escrow/" + first + "/fund")
                .header("Authorization", "Bearer " + beta)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"1.00\"}")
        ).andExpect(status().isForbidden());

        fund(alpha, first, "5000.00");
        settle("pr-a-" + tag);
        org.assertj.core.api.Assertions.assertThat(balance(beta, "EUR"))
            .isEqualByComparingTo(expectedPayout(beta, first));

        // Direction 2: roles invert — Beta buys from Alpha for 2,000 EUR.
        String second = createTrade(beta, alphaEmail, "2000.00", "pr-b-" + tag);
        mvc.perform(
            get("/api/v1/escrow/" + second)
                .header("Authorization", "Bearer " + alpha)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("seller"));
        fund(beta, second, "2000.00");
        settle("pr-b-" + tag);
        org.assertj.core.api.Assertions.assertThat(balance(alpha, "EUR"))
            .isEqualByComparingTo(expectedPayout(alpha, second));
    }

    @Test
    void invitedContractAttachesWhenCounterpartyRegisters() throws Exception {
        String tag = "" + System.nanoTime();
        String buyerEmail = "inv-buyer-" + tag + "@t.dev";
        String invitedEmail = "inv-seller-" + tag + "@t.dev";
        String buyer = onboard(buyerEmail, "Inviter " + tag, "UB-IB-" + tag, "1000.00");

        // Contract is created for an address that is not registered yet.
        mvc.perform(
            post("/api/v1/escrow")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"counterparty_email":"%s","target_amount":"500.00",
                     "tracking_type":"GITHUB_PR","tracking_key":"pr-i-%s"}
                    """.formatted(invitedEmail, tag)
                )
        )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("invited"));

        // The buyer's list shows the pending INVITED trade with the email
        // standing in for the seller name.
        MvcResult list = mvc
            .perform(
                get("/api/v1/escrow").header("Authorization", "Bearer " + buyer)
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                    "$[?(@.status=='INVITED' && @.seller_name=='" + invitedEmail + "')]"
                ).exists()
            )
            .andReturn();
        String contractId = JsonPath.read(
            list.getResponse().getContentAsString(),
            "$[0].id"
        );

        // An INVITED contract cannot be funded — there is no seller to pay.
        mvc.perform(
            post("/api/v1/escrow/" + contractId + "/fund")
                .header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deposit_amount\":\"1.00\"}")
        ).andExpect(status().isConflict());

        // The invitee registers and verifies — the trade attaches itself.
        String seller = onboard(
            invitedEmail,
            "Invitee " + tag,
            "UB-IS-" + tag,
            "10.00"
        );
        mvc.perform(
            get("/api/v1/escrow/" + contractId)
                .header("Authorization", "Bearer " + seller)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INITIATED"))
            .andExpect(jsonPath("$.role").value("seller"));

        // And the buyer can now fund the attached contract end to end.
        fund(buyer, contractId, "500.00");
        settle("pr-i-" + tag);
        org.assertj.core.api.Assertions.assertThat(balance(seller, "EUR"))
            .isEqualByComparingTo(expectedPayout(seller, contractId));
    }

    private static String signature(byte[] body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
            new javax.crypto.spec.SecretKeySpec(
                "test-gh-secret".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                ),
                "HmacSHA256"
            )
        );
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}
