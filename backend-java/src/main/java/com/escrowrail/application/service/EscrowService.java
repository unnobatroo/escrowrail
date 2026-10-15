package com.escrowrail.application.service;

import com.escrowrail.application.dto.Events.CounterpartyInvited;
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
import java.util.Optional;
import java.util.UUID;
import javax.money.MonetaryAmount;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrow contract coordinator: tenant-scoped reads, creation, and invites.
 * The money-moving legs live in {@link EscrowFundingService},
 * {@link EscrowSettlementService}, and {@link EscrowCancellationService};
 * pricing in {@link QuoteCalculator}. Mutations run in one transaction with
 * PESSIMISTIC_WRITE locks; events go through the Modulith outbox.
 */
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

    /**
     * Contract visible to its buyer or seller organization.
     *
     * @throws NotFoundException if absent or the entity is not a party
     */
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

    /**
     * Ledger entries in creation order, caller must be a party.
     *
     * @throws NotFoundException if absent or the entity is not a party
     */
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
            .findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT))
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

    /**
     * Counterparty invite via the outbox. Must run inside a transaction —
     * AFTER_COMMIT listeners miss events published without one.
     *
     * @throws NotFoundException if the inviter organization is absent
     */
    @Transactional
    public void inviteCounterparty(String email, UUID inviterEntityId) {
        Organization inviter = organizations
            .findById(inviterEntityId)
            .orElseThrow(() -> new NotFoundException("Organization not found"));
        events.publishEvent(
            new CounterpartyInvited(email, inviter.legalName())
        );
    }

    // ---------- lifecycle ----------

    /**
     * Creates an INITIATED contract after org, UBO, and FX checks. Target
     * amount is in the invoice currency.
     *
     * @throws NotFoundException if either organization is absent
     * @throws CircularOwnershipException if both organizations share a normalized UBO tax ID
     * @throws com.escrowrail.domain.exception.UnsupportedCurrencyPairException if the FX provider cannot quote the pair
     */
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
        // Reject pairs the FX provider cannot quote — the contract would
        // never be fundable.
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

    /**
     * INVITED contract for an unregistered counterparty; a later signup under
     * {@code invitedEmail} fills the seller slot via {@link #attachPendingInvites}.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EscrowContract createInvitedContract(
        UUID buyerEntityId,
        MonetaryAmount targetAmount,
        String sourceCurrency,
        TrackingType trackingType,
        String trackingKey,
        String invitedEmail
    ) {
        fx.getRate(sourceCurrency, targetAmount.getCurrency().getCurrencyCode());
        return contracts.save(
            EscrowContract.createInvited(
                buyerEntityId,
                targetAmount,
                sourceCurrency,
                trackingType,
                trackingKey,
                invitedEmail
            )
        );
    }

    /**
     * Attaches INVITED contracts for {@code email} to the new entity — called
     * inside the registration transaction so pending trades arrive with the account.
     */
    @Transactional
    public void attachPendingInvites(String email, UUID sellerEntityId) {
        for (EscrowContract c : contracts.findPendingInvites(email)) {
            c.attachSeller(sellerEntityId);
            contracts.save(c);
        }
    }

    // ---------- delegated legs ----------

    public EscrowContract fundContract(
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
