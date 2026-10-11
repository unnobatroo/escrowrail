package com.wise.escrow.infrastructure.persistence;

import com.wise.escrow.domain.model.EscrowContract;
import com.wise.escrow.domain.model.EscrowStatus;
import com.wise.escrow.domain.model.MoneyAmounts;
import com.wise.escrow.domain.model.TrackingType;
import com.wise.escrow.domain.repository.EscrowContractRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EscrowContractRepositoryAdapter implements EscrowContractRepository {

    private final JpaEscrowContractRepository jpa;

    public EscrowContractRepositoryAdapter(JpaEscrowContractRepository jpa) {
        this.jpa = jpa;
    }

    static EscrowContract toDomain(EscrowContractEntity e) {
        EscrowContract c = new EscrowContract(
                e.getId(),
                e.getBuyerId(),
                e.getSellerId(),
                e.getTargetCurrency(),
                e.getSourceCurrency(),
                MoneyAmounts.of(e.getTargetAmount(), e.getTargetCurrency()),
                e.getTrackingType(),
                e.getTrackingKey(),
                e.getCreatedAt());
        if (e.getStatus() != EscrowStatus.INITIATED && e.getDepositAmount() != null) {
            c.fund(
                    e.getBuyerAccountId(),
                    MoneyAmounts.of(e.getDepositAmount(), e.getSourceCurrency()),
                    MoneyAmounts.of(e.getEscrowedAmount(), e.getTargetCurrency()),
                    e.getSnapshotSpotRate(),
                    e.getVaultAccountId(),
                    e.getFundedAt());
            if (e.getStatus() == EscrowStatus.SETTLED) {
                c.settle();
            } else if (e.getStatus() == EscrowStatus.CANCELLED) {
                c.cancel();
            }
        } else if (e.getStatus() == EscrowStatus.CANCELLED) {
            c.cancel();
        }
        return c;
    }

    static EscrowContractEntity toEntity(EscrowContract c) {
        EscrowContractEntity e = new EscrowContractEntity();
        e.setId(c.id());
        e.setBuyerId(c.buyerId());
        e.setSellerId(c.sellerId());
        e.setBuyerAccountId(c.buyerAccountId());
        e.setVaultAccountId(c.vaultAccountId());
        e.setTargetCurrency(c.targetCurrency());
        e.setSourceCurrency(c.sourceCurrency());
        e.setTargetAmount(c.targetAmount().getNumber().numberValue(BigDecimal.class));
        e.setDepositAmount(
                c.depositAmount() == null
                        ? null
                        : c.depositAmount().getNumber().numberValue(BigDecimal.class));
        e.setSnapshotSpotRate(c.snapshotSpotRate());
        e.setEscrowedAmount(
                c.escrowedAmount() == null
                        ? null
                        : c.escrowedAmount().getNumber().numberValue(BigDecimal.class));
        e.setStatus(c.status());
        e.setTrackingType(c.trackingType());
        e.setTrackingKey(c.trackingKey());
        e.setFundedAt(c.fundedAt());
        e.setCreatedAt(c.createdAt());
        return e;
    }

    @Override
    public Optional<EscrowContract> findById(UUID id) {
        return jpa.findById(id).map(EscrowContractRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<EscrowContract> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id).map(EscrowContractRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<EscrowContract> findByIdForEntity(UUID id, UUID entityId) {
        return jpa.findByIdForEntity(id, entityId).map(EscrowContractRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<EscrowContract> findFundedByTracking(TrackingType trackingType, String trackingKey) {
        return jpa.findFundedByTracking(trackingType, trackingKey)
                .map(EscrowContractRepositoryAdapter::toDomain);
    }

    @Override
    public List<EscrowContract> findAllForEntity(UUID entityId) {
        return jpa.findAllForEntity(entityId).stream()
                .map(EscrowContractRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<EscrowContract> findAllForEntityAndStatus(UUID entityId, EscrowStatus status) {
        return jpa.findAllForEntityAndStatus(entityId, status).stream()
                .map(EscrowContractRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public EscrowContract save(EscrowContract contract) {
        return toDomain(jpa.save(toEntity(contract)));
    }
}
