package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentMapperTest {

    private final IncidentMapper mapper = new IncidentMapper();

    @Test
    void mapsErrorCodeAndDetails() {
        IncidentEntity entity = entity();
        entity.setErrorCode("CARD_DECLINED");
        entity.setDetails("java.lang.IllegalStateException: card declined\n\tat Charge.run");

        Incident dto = mapper.toDTO(entity);

        assertThat(dto.getId()).isEqualTo(entity.getId());
        assertThat(dto.getActivityId()).isEqualTo(entity.getActivityId());
        assertThat(dto.getMessage()).isEqualTo("card declined");
        assertThat(dto.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(dto.getDetails()).startsWith("java.lang.IllegalStateException");
        assertThat(dto.getCreatedAt()).isEqualTo(entity.getCreatedAt());
    }

    @Test
    void incidentRecordedBeforeTheFieldsHasThemEmpty() {
        Incident dto = mapper.toDTO(entity());

        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getDetails()).isNull();
    }

    private static IncidentEntity entity() {
        IncidentEntity entity = new IncidentEntity();
        entity.setId(UUID.randomUUID());
        entity.setActivityId(UUID.randomUUID());
        entity.setMessage("card declined");
        entity.setCreatedAt(Instant.parse("2026-09-23T10:00:00Z"));
        return entity;
    }
}
