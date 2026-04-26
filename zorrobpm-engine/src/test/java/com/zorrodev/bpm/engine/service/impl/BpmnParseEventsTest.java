package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnParseEventsTest {

    @Test
    void parseEvents() throws IOException {
        String bpmnFile = "src/test/files/process-with-events.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getElements()).isNotNull().hasSize(8);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getMessageStartEvents()).isNotNull().hasSize(1);
        assertThat(bpmn.getTimerStartEvents()).isNotNull().hasSize(1);

        BpmnElementModel messageStartEvent = bpmn.getElement("messageStartEventElement");
        assertThat(messageStartEvent.getId()).isEqualTo("messageStartEventElement");
        assertThat(messageStartEvent.getName()).isEqualTo("Message Start Event");
        assertThat(messageStartEvent.getType()).isEqualTo(BpmnElementType.MESSAGE_START_EVENT);

        BpmnElementModel messageCatchEventElement = bpmn.getElement("messageCatchEventElement");
        assertThat(messageCatchEventElement.getId()).isEqualTo("messageCatchEventElement");
        assertThat(messageCatchEventElement.getName()).isEqualTo("Message Catch Event");
        assertThat(messageCatchEventElement.getType()).isEqualTo(BpmnElementType.MESSAGE_CATCH_EVENT);

        BpmnElementModel timerStartEvent = bpmn.getElement("timerStartEventElement");
        assertThat(timerStartEvent.getId()).isEqualTo("timerStartEventElement");
        assertThat(timerStartEvent.getName()).isEqualTo("Timer Start Event");
        assertThat(timerStartEvent.getType()).isEqualTo(BpmnElementType.TIMER_START_EVENT);

        BpmnElementModel timerCatchEventDateElement = bpmn.getElement("timerCatchEventElement_Date");
        assertThat(timerCatchEventDateElement.getId()).isEqualTo("timerCatchEventElement_Date");
        assertThat(timerCatchEventDateElement.getName()).isEqualTo("Timer Catch Event - Date");
        assertThat(timerCatchEventDateElement.getType()).isEqualTo(BpmnElementType.TIMER_CATCH_EVENT);
        assertThat(timerCatchEventDateElement.getExtensions().getTimerEventExtension().getType()).isEqualTo(TimerEventType.DATE);

        BpmnElementModel timerCatchEventDurationElement = bpmn.getElement("timerCatchEventElement_Duration");
        assertThat(timerCatchEventDurationElement.getId()).isEqualTo("timerCatchEventElement_Duration");
        assertThat(timerCatchEventDurationElement.getName()).isEqualTo("Timer Catch Event - Duration");
        assertThat(timerCatchEventDurationElement.getType()).isEqualTo(BpmnElementType.TIMER_CATCH_EVENT);
        assertThat(timerCatchEventDurationElement.getExtensions().getTimerEventExtension().getType()).isEqualTo(TimerEventType.DURATION);

        BpmnElementModel messageThrowEventElement = bpmn.getElement("messageThrowEventElement");
        assertThat(messageThrowEventElement.getId()).isEqualTo("messageThrowEventElement");
        assertThat(messageThrowEventElement.getName()).isEqualTo("Message Throw Event");
        assertThat(messageThrowEventElement.getType()).isEqualTo(BpmnElementType.MESSAGE_THROW_EVENT);
    }
}
