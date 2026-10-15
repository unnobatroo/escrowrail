package com.escrowrail.infrastructure.web;

import com.escrowrail.infrastructure.web.dto.escrow.EscrowTradeResponse;
import com.escrowrail.infrastructure.web.dto.escrow.CounterpartyResponse;
import com.escrowrail.infrastructure.web.dto.escrow.CreateEscrowTradeRequest;
import com.escrowrail.infrastructure.web.dto.escrow.FundEscrowTradeRequest;
import com.escrowrail.infrastructure.web.dto.escrow.TradeInvitedResponse;
import com.escrowrail.infrastructure.web.dto.ledger.LedgerTransactionResponse;
import com.escrowrail.infrastructure.web.dto.escrow.QuotePreviewResponse;
import com.escrowrail.application.service.EscrowService;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.MoneyAmounts;
import com.escrowrail.domain.model.Organization;
import com.escrowrail.domain.repository.OrganizationRepository;
import com.escrowrail.infrastructure.security.TenantPrincipal;
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
    public EscrowController(
            EscrowService service,
            OrganizationRepository organizations) {
        this.service = service;
        this.organizations = organizations;
    }

    @GetMapping
    /** Lists every contract where the caller's entity is buyer or seller. */
    public List<EscrowTradeResponse> list() {
        UUID me = TenantPrincipal.entityId();
        return service.listForEntity(me).stream().map(c -> view(c, me)).toList();
    }

    @GetMapping("/{id}")
    /** Returns one contract visible to the caller's entity. */
    public EscrowTradeResponse get(@PathVariable UUID id) {
        return view(service.getForEntity(id, TenantPrincipal.entityId()), TenantPrincipal.entityId());
    }

    @GetMapping("/{id}/audit")
    /** Returns the hash-chained ledger entries for a contract the caller is party to. */
    public List<LedgerTransactionResponse> audit(@PathVariable UUID id) {
        return service.auditForEntity(id, TenantPrincipal.entityId()).stream()
                .map(LedgerTransactionResponse::of)
                .toList();
    }

    @PostMapping
    /**
     * Creates a trade against a registered counterparty, or an INVITED
     * contract plus invite event when the email is not registered yet.
     */
    public ResponseEntity<?> create(@Valid @RequestBody CreateEscrowTradeRequest body) {
        UUID me = TenantPrincipal.entityId();
        var counterparty = service.resolveCounterparty(body.counterpartyEmail(), me);
        if (counterparty.isEmpty()) {
            // The trade still exists: an INVITED contract binds the invited
            // email and attaches to the counterparty's entity on signup.
            service.createInvitedContract(
                    me,
                    MoneyAmounts.of(body.targetAmount(), currency(body.targetCurrency(), "EUR")),
                    currency(body.sourceCurrency(), "USD"),
                    body.trackingType(),
                    body.trackingKey(),
                    body.counterpartyEmail());
            service.inviteCounterparty(body.counterpartyEmail(), me);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new TradeInvitedResponse(
                            "INVITED",
                            "Counterparty is not on Escrowrail yet — an invite was sent and the trade is waiting for them"));
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
    /** Debits the buyer, converts at spot, and vaults the target amount. */
    public EscrowTradeResponse fund(@PathVariable UUID id, @Valid @RequestBody FundEscrowTradeRequest body) {
        UUID me = TenantPrincipal.entityId();
        EscrowContract c = service.getForEntity(id, me);
        EscrowContract funded = service.fundContract(
                id, me, MoneyAmounts.of(body.depositAmount(), c.sourceCurrency()));
        // Explicit, unbundled cost summary: what left the buyer, what Escrowrail
        // kept, and what landed in the segregated vault.
        var q = service.quote(
                c.sourceCurrency(), c.targetCurrency(),
                c.targetAmount().getNumber().numberValue(BigDecimal.class));
        return view(funded, me, body.depositAmount(), q.platformFee(),
                funded.escrowedAmount().getNumber().numberValue(BigDecimal.class));
    }

    @PostMapping("/{id}/cancel")
    /** Buyer-only cancellation; funded contracts liquidate back at current spot. */
    public EscrowTradeResponse cancel(@PathVariable UUID id) {
        UUID me = TenantPrincipal.entityId();
        return view(service.cancelContract(id, me), me);
    }

    @GetMapping("/counterparty")
    /** Resolves a counterparty email to its organization plus the UBO collision flag. */
    public CounterpartyResponse counterparty(@RequestParam String email) {
        UUID me = TenantPrincipal.entityId();
        return service
                .resolveCounterparty(email, me)
                .map(cp -> new CounterpartyResponse(
                        email,
                        cp.organization().id(),
                        cp.organization().legalName(),
                        cp.organization().country(),
                        cp.sameUbo()))
                .orElseThrow(() -> new NotFoundException("No registered organization for this email"));
    }

    @GetMapping("/quote")
    /** Returns a cent-rounded source-deposit quote including the bank-cost comparison. */
    public QuotePreviewResponse quote(
            @RequestParam("target_amount") BigDecimal targetAmount,
            @RequestParam(value = "target_currency", defaultValue = "EUR") String targetCurrency,
            @RequestParam(value = "source_currency", defaultValue = "USD") String sourceCurrency) {
        TenantPrincipal.entityId(); // auth gate
        EscrowService.Quote q = service.quote(sourceCurrency, targetCurrency, targetAmount);
        var bank = q.traditionalBankEstimate();
        return new QuotePreviewResponse(
                q.sourceCurrency(),
                q.targetCurrency(),
                q.targetAmount(),
                q.midMarketRate(),
                q.principal(),
                q.platformFee(),
                q.totalDeposit(),
                bank.spreadCost(),
                bank.swiftFee(),
                bank.totalCost(),
                bank.totalCost().subtract(q.platformFee()));
    }

    private EscrowTradeResponse view(EscrowContract c, UUID me) {
        return view(c, me, null, null, null);
    }

    private EscrowTradeResponse view(
            EscrowContract c, UUID me,
            BigDecimal grossDebited, BigDecimal platformFee, BigDecimal netVaulted) {
        String buyerName = organizations.findById(c.buyerId()).map(Organization::legalName).orElse("?");
        String sellerName = c.sellerId() == null
                ? c.invitedEmail()
                : organizations.findById(c.sellerId()).map(Organization::legalName).orElse("?");
        return new EscrowTradeResponse(
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
                c.createdAt(),
                grossDebited,
                platformFee,
                netVaulted);
    }

    private static String currency(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(java.util.Locale.ROOT);
    }
}
