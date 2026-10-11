package com.wise.escrow.infrastructure.web;

import com.wise.escrow.application.dto.ApiDtos.ErrorView;
import com.wise.escrow.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorView> domain(DomainException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(new ErrorView(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorView> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(new ErrorView("ERR_VALIDATION", msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorView> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorView("ERR_FORBIDDEN", "Access denied"));
    }

    /** Framework-level client errors are 4xx, never 500. */
    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            javax.money.UnknownCurrencyException.class})
    ResponseEntity<ErrorView> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ErrorView("ERR_VALIDATION", "Malformed request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorView> unexpected(Exception e) {
        log.error("unhandled", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("ERR_INTERNAL", "Internal error"));
    }
}
