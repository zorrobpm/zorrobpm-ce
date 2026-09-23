package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.service.TimerExpressionService;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngineManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimerExpressionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-01-31T10:00:00Z");

    private final TimerExpressionService service = new TimerExpressionServiceImpl(
        new ScriptServiceImpl(new ScriptEngineManager().getEngineByName("feel"), FeelEngineBuilder.forJava().build(), new ObjectMapper()));

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "PT15M,      2026-01-31T10:15:00Z",
        "PT1H30M,    2026-01-31T11:30:00Z",
        "P1DT2H,     2026-02-01T12:00:00Z",
        "P1W,        2026-02-07T10:00:00Z",
        "P1M,        2026-02-28T10:00:00Z",
        "P1Y,        2027-01-31T10:00:00Z",
        "PT0.5S,     2026-01-31T10:00:00.500Z",
    })
    void durationLiteral(String expression, Instant expected) {
        assertThat(schedule(TimerEventType.DURATION, expression)).isEqualTo(TimerSchedule.once(expected));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "2026-10-01T09:00:00+05:00,          2026-10-01T04:00:00Z",
        "2026-10-01T09:00:00Z,               2026-10-01T09:00:00Z",
        "2026-10-01T09:00:00,                2026-10-01T09:00:00Z",
        "2026-10-01T09:00:00+02:00[Europe/Berlin], 2026-10-01T07:00:00Z",
        "2020-01-01T00:00:00Z,               2020-01-01T00:00:00Z",
    })
    void dateLiteral(String expression, Instant expected) {
        assertThat(schedule(TimerEventType.DATE, expression)).isEqualTo(TimerSchedule.once(expected));
    }

    @Test
    void boundedCycle() {
        assertThat(schedule(TimerEventType.CYCLE, "R3/PT10M"))
            .isEqualTo(new TimerSchedule(Instant.parse("2026-01-31T10:10:00Z"), "PT10M", 2));
        assertThat(schedule(TimerEventType.CYCLE, "R1/PT10M"))
            .isEqualTo(new TimerSchedule(Instant.parse("2026-01-31T10:10:00Z"), "PT10M", 0));
    }

    @Test
    void unboundedCycle() {
        assertThat(schedule(TimerEventType.CYCLE, "R/PT1H"))
            .isEqualTo(new TimerSchedule(Instant.parse("2026-01-31T11:00:00Z"), "PT1H", null));
    }

    @Test
    void nextFiringOfCycleCountsFromThePreviousDueTime() {
        assertThat(service.next("PT1H", Instant.parse("2026-01-31T11:00:00Z"))).isEqualTo(Instant.parse("2026-01-31T12:00:00Z"));
        assertThat(service.next("P1M", Instant.parse("2026-01-31T11:00:00Z"))).isEqualTo(Instant.parse("2026-02-28T11:00:00Z"));
    }

    @Test
    void feelDurationFromVariable() {
        ProcessVariable minutes = new ProcessVariable();
        minutes.setName("slaMinutes");
        minutes.setType(ProcessVariableType.LONG);
        minutes.setValue("5");

        TimerSchedule schedule = service.schedule(boundary(TimerEventType.DURATION, "=duration(\"PT\" + string(slaMinutes) + \"M\")"), List.of(minutes), NOW);

        assertThat(schedule).isEqualTo(TimerSchedule.once(Instant.parse("2026-01-31T10:05:00Z")));
    }

    @Test
    void feelYearsMonthsDuration() {
        assertThat(schedule(TimerEventType.DURATION, "=duration(\"P1M\")")).isEqualTo(TimerSchedule.once(Instant.parse("2026-02-28T10:00:00Z")));
    }

    @Test
    void feelStringLiteral() {
        assertThat(schedule(TimerEventType.DURATION, "=\"PT2H\"")).isEqualTo(TimerSchedule.once(Instant.parse("2026-01-31T12:00:00Z")));
        assertThat(schedule(TimerEventType.CYCLE, "=\"R2/PT1H\"")).isEqualTo(new TimerSchedule(Instant.parse("2026-01-31T11:00:00Z"), "PT1H", 1));
    }

    @Test
    void feelDateAndTime() {
        assertThat(schedule(TimerEventType.DATE, "=date and time(\"2026-10-01T09:00:00Z\")")).isEqualTo(TimerSchedule.once(Instant.parse("2026-10-01T09:00:00Z")));
        assertThat(schedule(TimerEventType.DATE, "=date and time(\"2026-10-01T09:00:00\")")).isEqualTo(TimerSchedule.once(Instant.parse("2026-10-01T09:00:00Z")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "DURATION | через час",
        "DURATION | PT0S",
        "DURATION | PT-1H",
        "DURATION | -PT1H",
        "DURATION | P",
        "DURATION | P1DT",
        "DURATION | =1 + 1",
        "DURATION | =missing.value",
        "DATE     | tomorrow",
        "DATE     | =duration(\"PT1H\")",
        "CYCLE    | 0 0 9 * * ?",
        "CYCLE    | R3/2026-10-01T09:00:00Z/PT1H",
        "CYCLE    | R0/PT1H",
        "CYCLE    | R3/PT0S",
        "CYCLE    | R99999999999/PT1H",
        "CYCLE    | =duration(\"PT1H\")",
    })
    void unsupportedValuesNameTheBoundaryEvent(TimerEventType type, String expression) {
        assertThatThrownBy(() -> schedule(type, expression))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Boundary event 'timeout'");
    }

    private TimerSchedule schedule(TimerEventType type, String expression) {
        return service.schedule(boundary(type, expression), List.of(), NOW);
    }

    private static BpmnElementModel boundary(TimerEventType type, String expression) {
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        timer.setType(type);
        timer.setExpression(expression);
        BpmnElementModel element = new BpmnElementModel();
        element.setId("timeout");
        element.setType(BpmnElementType.TIMER_BOUNDARY_EVENT);
        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setTimerEventExtension(timer);
        return element;
    }
}
