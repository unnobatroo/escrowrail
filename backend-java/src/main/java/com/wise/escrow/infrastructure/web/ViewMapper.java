package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.infrastructure.web.dto.ledger.AccountBalanceResponse;
import com.escrowrail.escrow.infrastructure.web.dto.profile.EntitySummaryResponse;
import com.escrowrail.escrow.domain.model.Account;
import com.escrowrail.escrow.domain.model.Organization;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ViewMapper {

    @Mapping(target = "id", expression = "java(a.id())")
    @Mapping(target = "entityId", expression = "java(a.entityId())")
    @Mapping(target = "accountType", expression = "java(a.type().name())")
    @Mapping(target = "currency", expression = "java(a.currency())")
    @Mapping(
            target = "balance",
            expression = "java(a.balance().getNumber().numberValue(java.math.BigDecimal.class))")
    AccountBalanceResponse toAccountView(Account a);

    @Mapping(target = "id", expression = "java(o.id())")
    @Mapping(target = "legalName", expression = "java(o.legalName())")
    @Mapping(target = "uboTaxId", expression = "java(o.uboTaxId().value())")
    @Mapping(target = "country", expression = "java(o.country())")
    EntitySummaryResponse toEntityView(Organization o);
}
