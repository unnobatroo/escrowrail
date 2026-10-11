package com.wise.escrow.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository {
    record Record(
        UUID id,
        String idempotencyKey,
        int responseStatus,
        String responseBody,
        Instant createdAt
    ) {}

    Optional<Record> findByKey(String key);

    Record save(Record record);

    void delete(Record record);
}
