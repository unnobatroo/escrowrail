package com.escrowrail.infrastructure.security;

import com.escrowrail.domain.repository.IdempotencyRecordRepository;
import com.escrowrail.domain.repository.IdempotencyRecordRepository.Record;
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

    /** POST idempotency: keyed by X-Idempotency-Key else SHA-256(path+body); claims taken pre-handler, stored 2xx replayed byte-for-byte, errors never cached, webhooks excluded. */
@Component
@Order(1)
public class IdempotencyFilter extends OncePerRequestFilter {

    /** How long a completed response replays (bounded by the sweeper). */
    public static final Duration REPLAY_WINDOW = Duration.ofHours(24);

    /** Abandoned claims become reclaimable after this. */
    public static final Duration CLAIM_TTL = Duration.ofSeconds(300);
    private static final int IN_FLIGHT = 0;
    private static final String KEY_HEADER = "X-Idempotency-Key";
    private static final String REPLAY_HEADER = "X-Idempotent-Replay";
    /** Bodies beyond this skip buffering and dedup. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final IdempotencyRecordRepository records;

    public IdempotencyFilter(IdempotencyRecordRepository records) {
        this.records = records;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Bearer tokens must never be cached.
        boolean mutating = switch (request.getMethod().toUpperCase(java.util.Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
        return (
            !mutating ||
            request.getRequestURI().startsWith("/webhooks/") ||
            request.getRequestURI().startsWith("/api/v1/auth/")
        );
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        // Reject oversized bodies — unbounded pre-auth buffering exhausts the heap.
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        // Chunked/unknown length: read at most bound + 1 byte.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        RepeatableBodyRequestWrapper wrappedRequest =
            new RepeatableBodyRequestWrapper(request, body);
        ContentCachingResponseWrapper wrappedResponse =
            new ContentCachingResponseWrapper(response);
        // Caller identity is in the dedup key — same body+path from another
        // tenant must not replay. Pre-auth, the credential hash is the only
        // unforgeable identity.
        String explicitKey = request.getHeader(KEY_HEADER);
        String auth = request.getHeader("Authorization");
        String identityHash = sha256(
            (auth == null ? "" : auth).getBytes(StandardCharsets.UTF_8)
        );
        // Hash keeps the key at 64 chars regardless of URI length (VARCHAR(200)).
        String key = sha256(
            (
                request.getRequestURI() +
                ":" +
                (explicitKey != null ? explicitKey : sha256(body)) +
                ":" +
                identityHash
            ).getBytes(StandardCharsets.UTF_8)
        );

        Optional<Record> stored = records.findByKey(key).filter(this::fresh);
        if (stored.isPresent() && stored.get().responseStatus() != IN_FLIGHT) {
            writeReplay(response, stored.get());
            return;
        }

        Record claim = new Record(
            UUID.randomUUID(),
            key,
            IN_FLIGHT,
            "",
            Instant.now()
        );
        if (stored.isPresent() || !tryClaim(claim)) {
            // Claim held — wait briefly, then replay.
            Optional<Record> done = awaitCompleted(key);
            if (done.isPresent() && done.get().responseStatus() != IN_FLIGHT) {
                writeReplay(response, done.get());
            } else {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response
                    .getWriter()
                    .write(
                        "{\"code\":\"ERR_REQUEST_IN_FLIGHT\"," +
                            "\"message\":\"An identical request is still being processed\"}"
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
            // Don't cache failures — release the claim so a retry runs fresh.
            records.delete(claim);
        }
        wrappedResponse.copyBodyToResponse();
    }

    /** Saves a pending claim, retrying once after evicting an expired row (expired rows block the key's unique constraint forever). */
    private boolean tryClaim(Record claim) {
        try {
            records.save(claim);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Lost the race — unless the row expired; evict and reclaim.
            Optional<Record> loser = records.findByKey(claim.idempotencyKey());
            if (loser.isPresent() && !fresh(loser.get())) {
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

    /** Polls a claim ~3s, backing off 50→500ms so a slow handler doesn't multiply duplicate queries. */
    private Optional<Record> awaitCompleted(String key) {
        long backoffMs = 50;
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Record> rec = records.findByKey(key).filter(this::fresh);
            if (rec.isEmpty() || rec.get().responseStatus() != IN_FLIGHT) {
                return rec;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            backoffMs = Math.min(backoffMs * 2, 500);
        }
        return records.findByKey(key).filter(this::fresh);
    }

    /** Live check: claims age out fast, responses don't. */
    private boolean fresh(Record r) {
        Duration ttl = r.responseStatus() == IN_FLIGHT ? CLAIM_TTL : REPLAY_WINDOW;
        return r.createdAt().isAfter(Instant.now().minus(ttl));
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

    /** Replays a pre-read body so handlers see it intact. */
    private static final class RepeatableBodyRequestWrapper
        extends HttpServletRequestWrapper
    {

        private final byte[] body;

        RepeatableBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        /** {@inheritDoc} */
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                /** {@inheritDoc} */
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                /** {@inheritDoc} */
                public boolean isReady() {
                    return true;
                }

                @Override
                /** {@inheritDoc} */
                public void setReadListener(ReadListener listener) {}

                @Override
                /** {@inheritDoc} */
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        /** {@inheritDoc} */
        public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)
            );
        }
    }
}
