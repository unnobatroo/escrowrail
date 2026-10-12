package com.wise.escrow.infrastructure.web;

import com.wise.escrow.infrastructure.web.dto.ledger.AccountBalanceResponse;
import com.wise.escrow.infrastructure.web.dto.ledger.LedgerTransactionResponse;
import com.wise.escrow.domain.exception.NotFoundException;
import com.wise.escrow.domain.repository.AccountRepository;
import com.wise.escrow.domain.repository.LedgerRepository;
import com.wise.escrow.infrastructure.security.TenantPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final ViewMapper views;

    /** Creates a new AccountController. */
    public AccountController(AccountRepository accounts, LedgerRepository ledger, ViewMapper views) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.views = views;
    }

    @GetMapping
    /** Lists the caller entity's currency accounts and balances. */
    public List<AccountBalanceResponse> list() {
        return accounts.findAllByEntityId(TenantPrincipal.entityId()).stream()
                .map(views::toAccountView)
                .toList();
    }

    @GetMapping("/{id}")
    /** Returns one account owned by the caller entity. */
    public AccountBalanceResponse get(@PathVariable UUID id) {
        return accounts
                .findById(id)
                .filter(a -> TenantPrincipal.entityId().equals(a.entityId()))
                .map(views::toAccountView)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    @GetMapping("/{id}/ledger")
    /** Lists the ledger entries touching one owned account. */
    public List<LedgerTransactionResponse> ledger(@PathVariable UUID id) {
        accounts.findById(id)
                .filter(a -> TenantPrincipal.entityId().equals(a.entityId()))
                .orElseThrow(() -> new NotFoundException("Account not found"));
        return ledger.findByAccountId(id).stream()
                .map(LedgerTransactionResponse::of)
                .toList();
    }
}
