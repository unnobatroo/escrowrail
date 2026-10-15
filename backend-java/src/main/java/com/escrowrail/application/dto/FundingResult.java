package com.escrowrail.application.dto;

import com.escrowrail.domain.model.EscrowContract;
import java.math.BigDecimal;

/** Funding outcome: the contract plus the fee actually charged. */
public record FundingResult(EscrowContract contract, BigDecimal platformFee) {}
