package com.escrowrail;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared real-Postgres context — H2 fakes PESSIMISTIC_WRITE and would mask the concurrency under test. One static container per JVM (per-class lifecycle breaks the cached context). */
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
