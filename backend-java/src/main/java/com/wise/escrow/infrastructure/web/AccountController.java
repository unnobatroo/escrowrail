package com.wise.escrow.infrastructure.web;

import com.wise.escrow.application.dto.ApiDtos.AccountView;
import com.wise.escrow.application.dto.ApiDtos.LedgerEntryView;
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

    public AccountController(AccountRepository accounts, LedgerRepository ledger, ViewMapper views) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.views = views;
    }

    @GetMapping
    public List<AccountView> list() {
        return accounts.findAllByEntityId(TenantPrincipal.entityId()).stream()
                .map(views::toAccountView)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable UUID id) {
        return accounts
                .findById(id)
                .filter(a -> TenantPrincipal.entityId().equals(a.entityId()))
                .map(views::toAccountView)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    @GetMapping("/{id}/ledger")
    public List<LedgerEntryView> ledger(@PathVariable UUID id) {
        accounts.findById(id)
                .filter(a -> TenantPrincipal.entityId().equals(a.entityId()))
                .orElseThrow(() -> new NotFoundException("Account not found"));
        return ledger.findByAccountId(id).stream()
                .map(e -> new LedgerEntryView(
                        e.id(), e.debitAccountId(), e.creditAccountId(),
                        e.amount(), e.currency(), e.description(), e.createdAt()))
                .toList();
    }
}
