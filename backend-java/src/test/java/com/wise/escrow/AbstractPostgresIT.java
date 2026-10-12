package com.escrowrail.escrow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared real-PostgreSQL context. H2 is deliberately not used — it does
 * not implement PESSIMISTIC_WRITE semantics faithfully and would mask the
 * concurrency guarantees this suite exists to prove.
 *
 * The container is started once in the static initializer and lives for the
 * whole JVM (ryuk reaps it at exit). @Testcontainers' per-class lifecycle is
 * avoided deliberately: it would stop the container when each test class
 * finishes while the cached Spring context keeps the first container's port.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        "postgres:16-alpine"
    )
        // Keep the test container small — constrained Docker
        // VMs (e.g. OrbStack) OOM-kill the default PG config
        // mid-suite.
        .withCommand(
            "postgres",
            "-c",
            "shared_buffers=64MB",
            "-c",
            "work_mem=4MB",
            "-c",
            "max_connections=50"
        );

    static {
        POSTGRES.start();
    }
}
