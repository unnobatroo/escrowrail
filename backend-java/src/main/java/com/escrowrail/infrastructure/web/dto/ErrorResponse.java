package com.escrowrail.infrastructure.web.dto;

/** Structured error body: stable machine code plus human message. */
public record ErrorResponse(String code, String message) {}
