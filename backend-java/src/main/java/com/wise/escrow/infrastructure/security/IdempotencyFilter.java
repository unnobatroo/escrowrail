package com.wise.escrow.infrastructure.security;

import com.wise.escrow.domain.repository.IdempotencyRecordRepository;
import com.wise.escrow.domain.repository.IdempotencyRecordRepository.Record;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Endpoint idempotency: POSTs are keyed by X-Idempotency-Key, or by a
 * SHA-256 of path+body when the header is absent. A stored response is
 * replayed byte-for-byte with X-Idempotent-Replay: true — side effects
 * never re-execute. Records older than the 300-second replay window are
 * ignored so stale keys cannot shadow new work.
 */
@Component
@Order(1)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final Duration REPLAY_WINDOW = Duration.ofSeconds(300);
    private static final String KEY_HEADER = "X-Idempotency-Key";
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private final IdempotencyRecordRepository records;

    public IdempotencyFilter(IdempotencyRecordRepository records) {
        this.records = records;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        RepeatableBodyRequestWrapper wrappedRequest = new RepeatableBodyRequestWrapper(request, body);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        // Caller identity is part of the dedup key: two different tenants
        // POSTing the same (possibly empty) body to the same path must not
        // replay each other's responses.
        String explicitKey = request.getHeader(KEY_HEADER);
        String auth = request.getHeader("Authorization");
        String identityHash = sha256((auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8));
        String key = request.getRequestURI() + ":"
                + (explicitKey != null ? explicitKey : sha256(body))
                + ":" + identityHash;

        if (replay(key, response)) {
            return;
        }

        chain.doFilter(wrappedRequest, wrappedResponse);

        int status = wrappedResponse.getStatus();
        if (status < 500) {
            records.save(new Record(
                    UUID.randomUUID(),
                    key,
                    status,
                    new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8),
                    Instant.now()));
        }
        wrappedResponse.copyBodyToResponse();
    }

    private boolean replay(String key, HttpServletResponse response) throws IOException {
        return records.findByKey(key)
                .filter(r -> r.createdAt().isAfter(Instant.now().minus(REPLAY_WINDOW)))
                .map(r -> {
                    response.setStatus(r.responseStatus());
                    response.setContentType("application/json");
                    response.setHeader(REPLAY_HEADER, "true");
                    try {
                        response.getWriter().write(r.responseBody());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                })
                .orElse(false);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replays a pre-read body so downstream handlers see it intact. */
    private static final class RepeatableBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        RepeatableBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {}

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
