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
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Endpoint idempotency: POSTs are keyed by X-Idempotency-Key, or by a
 * SHA-256 of path+body when the header is absent. The key is claimed
 * (pending row) BEFORE the handler runs so a concurrent duplicate can
 * never re-execute side effects; on completion the stored response is
 * replayed byte-for-byte with X-Idempotent-Replay: true. Only 2xx
 * responses are cached — errors stay retryable, and settlement webhooks
 * are excluded entirely (their own domain idempotency applies).
 */
@Component
@Order(1)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final Duration REPLAY_WINDOW = Duration.ofSeconds(300);
    private static final int IN_FLIGHT = 0;
    private static final String KEY_HEADER = "X-Idempotency-Key";
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private final IdempotencyRecordRepository records;

    public IdempotencyFilter(IdempotencyRecordRepository records) {
        this.records = records;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
            || request.getRequestURI().startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        RepeatableBodyRequestWrapper wrappedRequest =
            new RepeatableBodyRequestWrapper(request, body);
        ContentCachingResponseWrapper wrappedResponse =
            new ContentCachingResponseWrapper(response);
        // Caller identity is part of the dedup key: two different tenants
        // POSTing the same (possibly empty) body to the same path must not
        // replay each other's responses.
        String explicitKey = request.getHeader(KEY_HEADER);
        String auth = request.getHeader("Authorization");
        String identityHash = sha256(
            (auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8)
        );
        // Hash the composite so the key stays a fixed 64 chars regardless
        // of URI length (the column is VARCHAR(200); a long URI + two
        // hashes would overflow it).
        String key = sha256(
            (request.getRequestURI() +
                    ":" +
                    (explicitKey != null ? explicitKey : sha256(body)) +
                    ":" +
                    identityHash)
                .getBytes(StandardCharsets.UTF_8)
        );

        Optional<Record> stored = records.findByKey(key).filter(this::fresh);
        if (stored.isPresent() && stored.get().responseStatus() != IN_FLIGHT) {
            writeReplay(response, stored.get());
            return;
        }

        Record claim = new Record(
            UUID.randomUUID(), key, IN_FLIGHT, "", Instant.now());
        if (stored.isPresent() || !tryClaim(claim)) {
            // Another request holds the claim — wait briefly for its
            // result, then replay it instead of re-executing.
            Optional<Record> done = awaitCompleted(key);
            if (done.isPresent() && done.get().responseStatus() != IN_FLIGHT) {
                writeReplay(response, done.get());
            } else {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"code\":\"ERR_REQUEST_IN_FLIGHT\","
                        + "\"message\":\"An identical request is still being processed\"}"
                );
            }
            return;
        }

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } catch (ServletException | IOException | RuntimeException e) {
            records.delete(claim);
            throw e;
        }

        int status = wrappedResponse.getStatus();
        if (status >= 200 && status < 300) {
            records.save(
                new Record(
                    claim.id(),
                    key,
                    status,
                    new String(
                        wrappedResponse.getContentAsByteArray(),
                        StandardCharsets.UTF_8
                    ),
                    Instant.now()
                )
            );
        } else {
            // Do not cache failures — the claim is released so a corrected
            // retry executes fresh.
            records.delete(claim);
        }
        wrappedResponse.copyBodyToResponse();
    }

    private boolean tryClaim(Record claim) {
        try {
            records.save(claim);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Lost the race — unless the row is a stale claim from a
            // crashed request, in which case evict it and claim again.
            Optional<Record> loser = records.findByKey(claim.idempotencyKey());
            if (loser.isPresent() && loser.get().responseStatus() == IN_FLIGHT
                    && !fresh(loser.get())) {
                records.delete(loser.get());
                try {
                    records.save(claim);
                    return true;
                } catch (DataIntegrityViolationException ignored) {
                    return false;
                }
            }
            return false;
        }
    }

    private Optional<Record> awaitCompleted(String key) {
        for (int i = 0; i < 30; i++) {
            Optional<Record> rec = records.findByKey(key).filter(this::fresh);
            if (rec.isEmpty() || rec.get().responseStatus() != IN_FLIGHT) {
                return rec;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return records.findByKey(key).filter(this::fresh);
    }

    private boolean fresh(Record r) {
        return r.createdAt().isAfter(Instant.now().minus(REPLAY_WINDOW));
    }

    private void writeReplay(HttpServletResponse response, Record r)
        throws IOException {
        response.setStatus(r.responseStatus());
        response.setContentType("application/json");
        response.setHeader(REPLAY_HEADER, "true");
        response.getWriter().write(r.responseBody());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replays a pre-read body so downstream handlers see it intact. */
    private static final class RepeatableBodyRequestWrapper
        extends HttpServletRequestWrapper
    {

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
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)
            );
        }
    }
}
