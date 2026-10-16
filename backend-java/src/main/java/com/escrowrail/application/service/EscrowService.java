package com.escrowrail.application.service;

import com.escrowrail.application.event.CounterpartyInvited;
import com.escrowrail.application.dto.FundingResult;
import com.escrowrail.application.dto.Quote;
import com.escrowrail.domain.exception.CircularOwnershipException;
import com.escrowrail.domain.exception.NotFoundException;
import com.escrowrail.domain.model.EscrowContract;
import com.escrowrail.domain.model.Organization;
import com.escrowrail.domain.model.TrackingType;
import com.escrowrail.domain.repository.AppUserRepository;
import com.escrowrail.domain.repository.EscrowContractRepository;
import com.escrowrail.domain.repository.LedgerRepository;
import com.escrowrail.domain.repository.LedgerRepository.LedgerEntry;
import com.escrowrail.domain.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.escrowrail.application.port.FxRateProvider;

/** Escrow coordinator: tenant reads, creation, invites. Money legs in the Funding/Settlement/Cancellation services; pricing in {@link QuoteCalculator}. */
@Service
public class EscrowService {

    private final EscrowContractRepository contracts;
    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final LedgerRepository ledger;
    private final FxRateProvider fx;
    private final ApplicationEventPublisher events;
    private final QuoteCalculator quotes;
    private final EscrowFundingService funding;
    private final EscrowSettlementService settlement;
    private final EscrowCancellationService cancellation;

    public EscrowService(
        EscrowContractRepository contracts,
        OrganizationRepository organizations,
        AppUserRepository users,
        LedgerRepository ledger,
        FxRateProvider fx,
        ApplicationEventPublisher events,
        QuoteCalculator quotes,
        EscrowFundingService funding,
        EscrowSettlementService settlement,
        EscrowCancellationService cancellation
    ) {
        this.contracts = contracts;
        this.organizations = organizations;
        this.users = users;
        this.ledger = ledger;
        this.fx = fx;
        this.events = events;
        this.quotes = quotes;
        this.funding = funding;
        this.settlement = settlement;
        this.cancellation = cancellation;
    }

    // ---------- reads (tenant-scoped) ----------

    /** Contract visible to a party org; 404s for absent/non-party. */
    @Transactional(readOnly = true)
    public EscrowContract getForEntity(UUID contractId, UUID entityId) {
        return contracts
            .findByIdForEntity(contractId, entityId)
            .orElseThrow(() ->
                new NotFoundException("Contract not found: " + contractId)
            );
    }

    @Transactional(readOnly = true)
    /** Contracts where the entity is buyer or seller. */
    public List<EscrowContract> listForEntity(UUID entityId) {
        return contracts.findAllForEntity(entityId);
    }

    /** Ledger entries in creation order; caller must be a party. */
    @Transactional(readOnly = true)
    public List<LedgerEntry> auditForEntity(UUID contractId, UUID entityId) {
        getForEntity(contractId, entityId); // tenancy gate before the trail
        return ledger.findByContractIdOrderByCreatedAt(contractId);
    }

    /** Looks up a counterparty by normalized email. */
    @Transactional(readOnly = true)
    public Optional<Counterparty> resolveCounterparty(
        String email,
        UUID requesterEntityId
    ) {
        return users
            .findByEmail(email.strip().toLowerCase(Locale.ROOT))
            .flatMap(u -> organizations.findById(u.entityId()))
            .map(org ->
                new Counterparty(
                    org,
                    organizations
                        .findById(requesterEntityId)
                        .map(me -> me.uboTaxId().equals(org.uboTaxId()))
                        .orElse(false)
                )
            );
    }

    public record Counterparty(Organization organization, boolean sameUbo) {}

    // ---------- lifecycle ----------

    /** Creates an INITIATED contract after org, UBO, and FX checks; target amount is in the invoice currency. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createContract(
        UUID buyerEntityId,
        UUID sellerEntityId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey
    ) {
        Organization buyer = organizations
            .findById(buyerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Buyer organization not found")
            );
        Organization seller = organizations
            .findById(sellerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Seller organization not found")
            );
        if (buyer.uboTaxId().equals(seller.uboTaxId())) {
            throw new CircularOwnershipException(
                "Buyer and seller share UBO tax id " + buyer.uboTaxId().value()
            );
        }
        // Reject unquotable pairs — the contract could never fund.
        fx.getRate(sourceCurrency, targetAmount.getCurrency().getCurrencyCode());
        return contracts.save(
            EscrowContract.create(
                buyerEntityId,
                sellerEntityId,
                targetAmount,
                sourceCurrency,
                trackingType,
                trackingKey
            )
        );
    }

    /** INVITED contract + invite event in one tx — a partial pair would orphan the trade. Signup under {@code invitedEmail} fills the seller slot via {@link #attachPendingInvites}. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createInvitedContract(
        UUID buyerEntityId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey,
        String invitedEmail
    ) {
        String normalizedEmail = invitedEmail.strip().toLowerCase(Locale.ROOT);
        Organization inviter = organizations
            .findById(buyerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Buyer organization not found")
            );
        fx.getRate(sourceCurrency, targetAmount.getCurrency().getCurrencyCode());
        EscrowContract saved = contracts.save(
            EscrowContract.createInvited(
                buyerEntityId,
                targetAmount,
                sourceCurrency,
                trackingType,
                trackingKey,
                normalizedEmail
            )
        );
        events.publishEvent(
            new CounterpartyInvited(normalizedEmail, inviter.legalName())
        );
        return saved;
    }

    /** Attaches INVITED contracts for {@code email} — called from {@code verifyEmail} once ownership is proven; the UBO check repeats as the buyer is only known now. */
    @Transactional
    public void attachPendingInvites(String email, UUID sellerEntityId) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        Organization seller = organizations
            .findById(sellerEntityId)
            .orElseThrow(() ->
                new NotFoundException("Seller organization not found")
            );
        for (EscrowContract c : contracts.findPendingInvites(normalizedEmail)) {
            Organization buyer = organizations
                .findById(c.buyerId())
                .orElseThrow(() ->
                    new NotFoundException("Buyer organization not found")
                );
            if (buyer.uboTaxId().equals(seller.uboTaxId())) {
                continue; // never pair circular owners — invite stays unattached
            }
            c.attachSeller(sellerEntityId);
            contracts.save(c);
        }
    }

    // ---------- delegated legs ----------

    public FundingResult fundContract(
        UUID contractId,
        UUID actorEntityId,
        MonetaryAmount deposit
    ) {
        return funding.fundContract(contractId, actorEntityId, deposit);
    }

    public EscrowContract settleMilestone(UUID contractId) {
        return settlement.settleMilestone(contractId);
    }

    public EscrowContract cancelContract(UUID contractId, UUID actorEntityId) {
        return cancellation.cancelContract(contractId, actorEntityId);
    }

    public Quote quote(
        String sourceCurrency,
        String targetCurrency,
        BigDecimal targetAmount
    ) {
        return quotes.quote(sourceCurrency, targetCurrency, targetAmount);
    }
}
