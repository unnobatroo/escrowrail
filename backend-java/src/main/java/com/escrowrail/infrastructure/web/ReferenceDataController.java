package com.escrowrail.infrastructure.web;

import com.escrowrail.domain.model.Account;
import com.escrowrail.domain.model.AccountType;
import com.escrowrail.domain.repository.AccountRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reference data. Supported currencies derive from seeded clearing accounts — rails that can actually move, never a hardcoded list. */
@RestController
@RequestMapping("/api/v1/currencies")
public class ReferenceDataController {

    private final AccountRepository accounts;
    public ReferenceDataController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    /** Supported currencies, sorted — one row per clearing account. */
    public Map<String, List<String>> supported() {
        List<String> currencies = accounts
            .findSystemAccounts(AccountType.ESCROWRAIL_CLEARING)
            .stream()
            .map(Account::currency)
            .sorted()
            .toList();
        return Map.of("currencies", currencies);
    }
}
