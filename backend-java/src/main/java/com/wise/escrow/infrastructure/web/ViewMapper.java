package com.escrowrail.escrow.infrastructure.web;

import com.escrowrail.escrow.application.dto.ApiDtos.AccountView;
import com.escrowrail.escrow.application.dto.ApiDtos.EntityView;
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
    AccountView toAccountView(Account a);

    @Mapping(target = "id", expression = "java(o.id())")
    @Mapping(target = "legalName", expression = "java(o.legalName())")
    @Mapping(target = "country", expression = "java(o.country())")
    EntityView toEntityView(Organization o);
}
