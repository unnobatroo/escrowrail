package com.wise.escrow.infrastructure.web;

import com.wise.escrow.domain.model.Account;
import com.wise.escrow.domain.model.AccountType;
import com.wise.escrow.domain.repository.AccountRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform reference data. The supported-currency list is derived from the
 * seeded clearing accounts — the set the funding rails can actually move,
 * never a hardcoded enum the client has to guess.
 */
@RestController
@RequestMapping("/api/v1/currencies")
public class ReferenceDataController {

    private final AccountRepository accounts;

    /** Creates a new ReferenceDataController. */
    public ReferenceDataController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    /** Supported currencies, sorted — one row per clearing account. */
    public Map<String, List<String>> supported() {
        List<String> currencies = accounts
            .findSystemAccounts(AccountType.WISE_CLEARING)
            .stream()
            .map(Account::currency)
            .sorted()
            .toList();
        return Map.of("currencies", currencies);
    }
}
