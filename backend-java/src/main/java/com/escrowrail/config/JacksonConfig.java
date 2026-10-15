package com.escrowrail.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wire format alignment: the API serializes monetary values as JSON strings
 * (matching the legacy Python service's Decimal output). This keeps the
 * React client's string-typed money fields — and exact-magnitude display —
 * intact instead of emitting lossy JSON numbers like 1.08543E3.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer bigDecimalAsPlainString() {
        return builder ->
                builder.serializerByType(
                        BigDecimal.class,
                        new JsonSerializer<BigDecimal>() {
                            @Override
                            public void serialize(
                                    BigDecimal value,
                                    JsonGenerator gen,
                                    SerializerProvider serializers)
                                    throws IOException {
                                gen.writeString(value.toPlainString());
                            }
                        });
    }
}
