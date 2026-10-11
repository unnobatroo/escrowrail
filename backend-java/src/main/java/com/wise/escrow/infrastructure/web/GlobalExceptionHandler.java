package com.wise.escrow.infrastructure.web;

import com.wise.escrow.application.dto.ApiDtos.EmailNotVerifiedView;
import com.wise.escrow.application.dto.ApiDtos.ErrorView;
import com.wise.escrow.domain.exception.DomainException;
import com.wise.escrow.domain.exception.EmailNotVerifiedException;
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

    @ExceptionHandler(EmailNotVerifiedException.class)
    ResponseEntity<EmailNotVerifiedView> notVerified(EmailNotVerifiedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new EmailNotVerifiedView(e.code(), e.getMessage(), e.email()));
    }

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
            javax.money.UnknownCurrencyException.class})
    ResponseEntity<ErrorView> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ErrorView("ERR_VALIDATION", "Malformed request"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorView> methodNotAllowed(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorView("ERR_METHOD_NOT_ALLOWED", "Method not allowed"));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorView> unsupportedMediaType(Exception e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorView("ERR_UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"));
    }

    /** Unmapped paths are 404, never 500 — profile-gated routes rely on it. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ErrorView> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView("ERR_NOT_FOUND", "Not found"));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ErrorView> conflict(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView("ERR_CONFLICT", "Resource was modified concurrently"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorView> unexpected(Exception e) {
        log.error("unhandled", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("ERR_INTERNAL", "Internal error"));
    }
}
