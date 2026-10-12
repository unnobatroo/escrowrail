package com.escrowrail.escrow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Self-service profile: tenant-scoped updates, tax-ID validation, the
 * one-time onboarding deposit credited through the ledger, and replay
 * rejection once onboarding has completed.
 */
@AutoConfigureMockMvc
class ProfileApiTest extends AbstractPostgresIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    /** Registers, verifies and returns the caller's JWT. */
    private String verifiedToken(String email) throws Exception {
        mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "email",
                            email,
                            "password",
                            "pw123456",
                            "legal_name",
                            "Profile Co",
                            "ubo_tax_id",
                            "UB-" + System.nanoTime(),
                            "country",
                            "US"
                        )
                    )
                )
        ).andExpect(status().isCreated());
        MvcResult tok = mvc
            .perform(get("/dev/verification-token").param("email", email))
            .andExpect(status().isOk())
            .andReturn();
        String raw = JsonPath.read(
            tok.getResponse().getContentAsString(),
            "$.token"
        );
        MvcResult verified = mvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + raw + "\"}")
            )
            .andExpect(status().isOk())
            .andReturn();
        return JsonPath.read(
            verified.getResponse().getContentAsString(),
            "$.access_token"
        );
    }

    private double usdBalance(String jwt) throws Exception {
        MvcResult res = mvc
            .perform(
                get("/api/v1/accounts").header("Authorization", "Bearer " + jwt)
            )
            .andExpect(status().isOk())
            .andReturn();
        String body = res.getResponse().getContentAsString();
        java.util.List<Map<String, Object>> rows = JsonPath.read(
            body,
            "$[?(@.currency == 'USD' && @.account_type == 'USER')]"
        );
        return new java.math.BigDecimal(
            rows.get(0).get("balance").toString()
        ).doubleValue();
    }

    @Test
    void profileEndpointRequiresAuthentication() throws Exception {
        mvc.perform(
            put("/api/v1/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"legal_entity_name\":\"X\"}")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void updateRenamesEntityValidatesTaxIdAndOnboardsOnce() throws Exception {
        String jwt = verifiedToken("prof-" + System.nanoTime() + "@t.dev");
        double before = usdBalance(jwt);

        // Bad tax ID is rejected before anything persists.
        mvc.perform(
            put("/api/v1/profile")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tax_id\":\"not-a-tax-id\"}")
        ).andExpect(status().isBadRequest());

        // Valid update: rename, set a formatted tax ID, take the one-time
        // onboarding deposit.
        mvc.perform(
            put("/api/v1/profile")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "legal_entity_name",
                            "SwiftFalcon Logistics Ltd",
                            "tax_id",
                            "GB-CRN-882190",
                            "initial_deposits",
                            Map.of("USD", "25000.00", "EUR", "5000.00")
                        )
                    )
                )
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.onboarding_completed").value(true))
            .andExpect(
                jsonPath("$.entity.legal_name")
                    .value("SwiftFalcon Logistics Ltd")
            )
            // TaxId canonicalizes separators for format-insensitive equality.
            .andExpect(jsonPath("$.entity.ubo_tax_id").value("GBCRN882190"));

        // The deposit moved through the ledger — USD balance grew by 25k.
        org.assertj.core.api.Assertions.assertThat(usdBalance(jwt))
            .isEqualTo(before + 25000.00);

        // Replays of the faucet are rejected: onboarding already completed.
        mvc.perform(
            put("/api/v1/profile")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initial_deposits\":{\"USD\":\"100.00\"}}")
        ).andExpect(status().isBadRequest());

        // Plain profile edits still work after onboarding.
        mvc.perform(
            put("/api/v1/profile")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"legal_entity_name\":\"Renamed Ltd\"}")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entity.legal_name").value("Renamed Ltd"));
    }

    @Test
    void supportedCurrenciesReflectClearingAccounts() throws Exception {
        String jwt = verifiedToken("cur-" + System.nanoTime() + "@t.dev");
        mvc.perform(
            get("/api/v1/currencies").header("Authorization", "Bearer " + jwt)
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencies").isArray())
            .andExpect(jsonPath("$.currencies[0]").value("EUR"))
            .andExpect(jsonPath("$.currencies[1]").value("USD"));
    }
}
