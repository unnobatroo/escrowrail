package com.wise.escrow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared real-PostgreSQL context. H2 is deliberately not used — it does
 * not implement PESSIMISTIC_WRITE semantics faithfully and would mask the
 * concurrency guarantees this suite exists to prove.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
