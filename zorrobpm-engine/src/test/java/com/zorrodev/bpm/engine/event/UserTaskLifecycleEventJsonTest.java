package com.zorrodev.bpm.engine.event;

import com.zorrodev.bpm.event.UserTaskEventType;
import com.zorrodev.bpm.event.UserTaskLifecycleEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserTaskLifecycleEventJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesEveryFieldOfTheContract() {
        UserTaskLifecycleEvent event = new UserTaskLifecycleEvent();
        UUID eventId = UUID.randomUUID();
        event.setEventId(eventId);
        event.setType(UserTaskEventType.CREATED);
        event.setOccurredAt(Instant.parse("2026-10-02T10:15:30Z"));
        event.setUserTaskId(UUID.randomUUID());
        event.setBpmnElementId("approve");
        event.setCandidateGroups(List.of("managers"));
        event.setCreatedAt(Instant.parse("2026-10-02T10:15:30Z"));
        event.setProcessDefinitionKey("order");
        event.setProcessDefinitionVersion(2);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "eventId", "type", "occurredAt", "userTaskId", "bpmnElementId", "name", "formKey", "assignee",
            "candidateUsers", "candidateGroups", "createdAt", "completedAt", "canceledAt", "loopIndex", "loopTotal",
            "processInstanceId", "processDefinitionId", "processDefinitionKey", "processDefinitionVersion");
        assertThat(json.get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(json.get("type").asString()).isEqualTo("CREATED");
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-02T10:15:30Z");
        assertThat(json.get("assignee").isNull()).isTrue();
        assertThat(json.get("completedAt").isNull()).isTrue();
        assertThat(json.get("candidateUsers").isArray()).isTrue();
        assertThat(json.get("candidateUsers")).isEmpty();
        assertThat(json.get("candidateGroups").get(0).asString()).isEqualTo("managers");
        assertThat(json.get("processDefinitionVersion").asInt()).isEqualTo(2);
    }
}
