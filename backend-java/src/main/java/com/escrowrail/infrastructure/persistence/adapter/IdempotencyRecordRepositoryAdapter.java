package com.escrowrail.infrastructure.persistence.adapter;

import com.escrowrail.domain.repository.IdempotencyRecordRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import com.escrowrail.infrastructure.persistence.entity.IdempotencyRecordEntity;
import com.escrowrail.infrastructure.persistence.jpa.JpaIdempotencyRecordRepository;

@Component
class IdempotencyRecordRepositoryAdapter implements IdempotencyRecordRepository {

    private final JpaIdempotencyRecordRepository jpa;

    IdempotencyRecordRepositoryAdapter(JpaIdempotencyRecordRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    /** {@inheritDoc} */
    public Optional<Record> findByKey(String key) {
        return jpa.findByIdempotencyKey(key).map(e -> new Record(
                e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt()));
    }

    @Override
    /** {@inheritDoc} */
    public Record save(Record record) {
        IdempotencyRecordEntity e = jpa.save(new IdempotencyRecordEntity(
                record.id(), record.idempotencyKey(), record.responseStatus(), record.responseBody(), record.createdAt()));
        return new Record(e.getId(), e.getIdempotencyKey(), e.getResponseStatus(), e.getResponseBody(), e.getCreatedAt());
    }

    @Override
    /** {@inheritDoc} */
    public void delete(Record record) {
        jpa.deleteById(record.id());
    }
}
