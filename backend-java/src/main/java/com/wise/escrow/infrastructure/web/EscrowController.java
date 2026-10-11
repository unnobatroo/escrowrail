package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.application.dto.ApiDtos.ContractView;
import com.escrowrail.escrow.application.dto.ApiDtos.CounterpartyView;
import com.escrowrail.escrow.application.dto.ApiDtos.CreateContractRequest;
import com.escrowrail.escrow.application.dto.ApiDtos.FundRequest;
import com.escrowrail.escrow.application.dto.ApiDtos.InvitedResponse;
import com.escrowrail.escrow.application.dto.ApiDtos.LedgerEntryView;
import com.escrowrail.escrow.application.dto.ApiDtos.QuoteView;
import com.escrowrail.escrow.application.service.EscrowService;
import com.escrowrail.escrow.domain.exception.NotFoundException;
import com.escrowrail.escrow.domain.model.EscrowContract;
import com.escrowrail.escrow.domain.model.MoneyAmounts;
import com.escrowrail.escrow.domain.model.Organization;
import com.escrowrail.escrow.domain.repository.OrganizationRepository;
import com.escrowrail.escrow.infrastructure.security.TenantPrincipal;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/escrow")
public class EscrowController {

    private final EscrowService service;
    private final OrganizationRepository organizations;

    /** Creates a new EscrowController. */
    public EscrowController(
            EscrowService service,
            OrganizationRepository organizations) {
        this.service = service;
        this.organizations = organizations;
    }

    @GetMapping
    /** L. */
    public List<ContractView> list() {
        UUID me = TenantPrincipal.entityId();
        return service.listForEntity(me).stream().map(c -> view(c, me)).toList();
    }

    @GetMapping("/{id}")
    /** G. */
    public ContractView get(@PathVariable UUID id) {
        return view(service.getForEntity(id, TenantPrincipal.entityId()), TenantPrincipal.entityId());
    }

    @GetMapping("/{id}/audit")
    /** A. */
    public List<LedgerEntryView> audit(@PathVariable UUID id) {
        return service.auditForEntity(id, TenantPrincipal.entityId()).stream()
                .map(e -> new LedgerEntryView(
                        e.id(), e.debitAccountId(), e.creditAccountId(),
                        e.amount(), e.currency(), e.description(), e.createdAt()))
                .toList();
    }

    @PostMapping
    /** C. */
    public ResponseEntity<?> create(@Valid @RequestBody CreateContractRequest body) {
        UUID me = TenantPrincipal.entityId();
        var counterparty = service.resolveCounterparty(body.counterpartyEmail(), me);
        if (counterparty.isEmpty()) {
            service.inviteCounterparty(body.counterpartyEmail(), me);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new InvitedResponse(
                            "invited",
                            "Counterparty is not on Escrowrail yet — an invite was sent"));
        }
        EscrowContract contract = service.createContract(
                me,
                counterparty.get().organization().id(),
                MoneyAmounts.of(body.targetAmount(), currency(body.targetCurrency(), "EUR")),
                currency(body.sourceCurrency(), "USD"),
                body.trackingType(),
                body.trackingKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(contract, me));
    }

    @PostMapping("/{id}/fund")
    /** F. */
    public ContractView fund(@PathVariable UUID id, @Valid @RequestBody FundRequest body) {
        UUID me = TenantPrincipal.entityId();
        EscrowContract c = service.getForEntity(id, me);
        return view(
                service.fundContract(
                        id, me, MoneyAmounts.of(body.depositAmount(), c.sourceCurrency())),
                me);
    }

    @PostMapping("/{id}/cancel")
    /** C. */
    public ContractView cancel(@PathVariable UUID id) {
        UUID me = TenantPrincipal.entityId();
        return view(service.cancelContract(id, me), me);
    }

    @GetMapping("/counterparty")
    /** C. */
    public CounterpartyView counterparty(@RequestParam String email) {
        UUID me = TenantPrincipal.entityId();
        return service
                .resolveCounterparty(email, me)
                .map(cp -> new CounterpartyView(
                        email,
                        cp.organization().id(),
                        cp.organization().legalName(),
                        cp.organization().country(),
                        cp.sameUbo()))
                .orElseThrow(() -> new NotFoundException("No registered organization for this email"));
    }

    @GetMapping("/quote")
    /** Q. */
    public QuoteView quote(
            @RequestParam BigDecimal targetAmount,
            @RequestParam(defaultValue = "EUR") String targetCurrency,
            @RequestParam(defaultValue = "USD") String sourceCurrency) {
        TenantPrincipal.entityId(); // auth gate
        EscrowService.Quote q = service.quote(sourceCurrency, targetCurrency, targetAmount);
        var bank = q.traditionalBankEstimate();
        return new QuoteView(
                q.sourceCurrency(),
                q.targetCurrency(),
                q.targetAmount(),
                q.midMarketRate(),
                q.principal(),
                q.wiseFee(),
                q.totalDeposit(),
                bank.spreadCost(),
                bank.swiftFee(),
                bank.totalCost(),
                bank.totalCost().subtract(q.wiseFee()));
    }

    private ContractView view(EscrowContract c, UUID me) {
        String buyerName = organizations.findById(c.buyerId()).map(Organization::legalName).orElse("?");
        String sellerName = organizations.findById(c.sellerId()).map(Organization::legalName).orElse("?");
        return new ContractView(
                c.id(),
                c.buyerId(),
                c.sellerId(),
                buyerName,
                sellerName,
                c.isBuyer(me) ? "buyer" : "seller",
                c.status(),
                c.targetCurrency(),
                c.targetAmount().getNumber().numberValue(BigDecimal.class),
                c.sourceCurrency(),
                c.depositAmount() == null
                        ? null
                        : c.depositAmount().getNumber().numberValue(BigDecimal.class),
                c.snapshotSpotRate(),
                c.escrowedAmount() == null
                        ? null
                        : c.escrowedAmount().getNumber().numberValue(BigDecimal.class),
                c.trackingType(),
                c.trackingKey(),
                c.fundedAt(),
                c.createdAt());
    }

    private static String currency(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(java.util.Locale.ROOT);
    }
}
