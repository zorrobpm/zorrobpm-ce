package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseServiceImplTest {

    @Test
    void testNoStartEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-1.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testNoEndEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-2.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testManyStartEvents() throws IOException {
        String bpmnFile = "src/test/files/process0-3.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();

        assertThatThrownBy(() -> {
            service.parse(bpmnStr);
        }).isInstanceOf(BpmnParseException.class);
    }

    @Test
    void testProcess1() throws IOException {
        // start event -> end event
        String bpmnFile = "src/test/files/process1.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getExecutionPlatformVersion()).isEqualTo("8.6.0");
        assertThat(bpmn.getName()).isEqualTo("Process 1");
        assertThat(bpmn.getKey()).isEqualTo("process1");
        assertThat(bpmn.getElements()).isNotNull().hasSize(2);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(1);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow1");
    }

    @Test
    void testProcess2() throws IOException {
        // start event -> service task -> end event
        String bpmnFile = "src/test/files/process2.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 2");
        assertThat(bpmn.getKey()).isEqualTo("process2");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel serviceTask = bpmn.getElement("serviceTask");
        assertThat(serviceTask.getId()).isEqualTo("serviceTask");
        assertThat(serviceTask.getName()).isEqualTo("Service Task");
        assertThat(serviceTask.getType()).isEqualTo(BpmnElementType.SERVICE_TASK);
        assertThat(serviceTask.getOutgoing()).isNotNull().hasSize(1);
        assertThat(serviceTask.getIncoming()).isNotNull().hasSize(1);
        assertThat(serviceTask.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(serviceTask.getOutgoing().get(0)).isEqualTo("flow2");
        ServiceTaskExtensionModel extension = serviceTask.getExtensions().getServiceTaskExtension();
        assertThat(extension).isNotNull();
        assertThat(extension.getJob()).isEqualTo("job1");
    }

    @Test
    void testProcess3() throws IOException {
        // start event -> user task -> end event
        String bpmnFile = "src/test/files/process3.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 3");
        assertThat(bpmn.getKey()).isEqualTo("process3");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel userTask = bpmn.getElement("userTask");
        assertThat(userTask.getId()).isEqualTo("userTask");
        assertThat(userTask.getName()).isEqualTo("User Task");
        assertThat(userTask.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(userTask.getOutgoing().get(0)).isEqualTo("flow2");
        UserTaskExtensionModel extension = userTask.getExtensions().getUserTaskExtension();
        assertThat(extension).isNotNull();
        assertThat(extension.getAssignee()).isEqualTo("assignee1");
        assertThat(extension.getCandidateUsers()).isEqualTo("candidateUser1,candidateUser2");
        assertThat(extension.getCandidateGroups()).isEqualTo("candidateGroup1,candidateGroup2");
        assertThat(extension.getFormKey()).isEqualTo("formKey1");
    }

    @Test
    void testProcess4() throws IOException {
        //                     -> user task 1
        // start event -> xor1                -> xor2 -> end event
        //                     -> user task 2
        String bpmnFile = "src/test/files/process4.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Process 4");
        assertThat(bpmn.getKey()).isEqualTo("process4");
        assertThat(bpmn.getElements()).isNotNull().hasSize(6);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(6);

        BpmnElementModel startEvent = bpmn.getElement("startEvent");
        assertThat(startEvent.getId()).isEqualTo("startEvent");
        assertThat(startEvent.getName()).isEqualTo("Start Event");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent");
        assertThat(endEvent.getId()).isEqualTo("endEvent");
        assertThat(endEvent.getName()).isEqualTo("End Event");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow6");

        BpmnElementModel userTask1 = bpmn.getElement("userTask1");
        assertThat(userTask1.getId()).isEqualTo("userTask1");
        assertThat(userTask1.getName()).isEqualTo("User Task 1");
        assertThat(userTask1.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask1.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask1.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask1.getIncoming().get(0)).isEqualTo("flow2");
        assertThat(userTask1.getOutgoing().get(0)).isEqualTo("flow4");

        BpmnElementModel userTask2 = bpmn.getElement("userTask2");
        assertThat(userTask2.getId()).isEqualTo("userTask2");
        assertThat(userTask2.getName()).isEqualTo("User Task 2");
        assertThat(userTask2.getType()).isEqualTo(BpmnElementType.USER_TASK);
        assertThat(userTask2.getOutgoing()).isNotNull().hasSize(1);
        assertThat(userTask2.getIncoming()).isNotNull().hasSize(1);
        assertThat(userTask2.getIncoming().get(0)).isEqualTo("flow3");
        assertThat(userTask2.getOutgoing().get(0)).isEqualTo("flow5");

        BpmnElementModel xor1 = bpmn.getElement("xor1");
        assertThat(xor1.getId()).isEqualTo("xor1");
        assertThat(xor1.getName()).isEqualTo("XOR 1");
        assertThat(xor1.getType()).isEqualTo(BpmnElementType.EXCLUSIVE_GATEWAY);
        assertThat(xor1.getIncoming()).isNotNull().hasSize(1);
        assertThat(xor1.getOutgoing()).isNotNull().hasSize(2);
        assertThat(xor1.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(xor1.getOutgoing()).contains("flow2", "flow3");
        assertThat(xor1.getExtensions()).isNotNull();
        assertThat(xor1.getExtensions().getExclusiveGatewayExtension()).isNotNull();
        assertThat(xor1.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId()).isEqualTo("flow2");

        BpmnElementModel xor2 = bpmn.getElement("xor2");
        assertThat(xor2.getId()).isEqualTo("xor2");
        assertThat(xor2.getName()).isEqualTo("XOR 2");
        assertThat(xor2.getType()).isEqualTo(BpmnElementType.EXCLUSIVE_GATEWAY);
        assertThat(xor2.getIncoming()).isNotNull().hasSize(2);
        assertThat(xor2.getOutgoing()).isNotNull().hasSize(1);
        assertThat(xor2.getIncoming()).contains("flow4", "flow5");
        assertThat(xor2.getOutgoing().get(0)).isEqualTo("flow6");

        BpmnFlowModel flow1 = bpmn.getFlow("flow1");
        assertThat(flow1.getFlowId()).isEqualTo("flow1");
        assertThat(flow1.getSourceRef()).isEqualTo("startEvent");
        assertThat(flow1.getTargetRef()).isEqualTo("xor1");

        BpmnFlowModel flow2 = bpmn.getFlow("flow2");
        assertThat(flow2.getFlowId()).isEqualTo("flow2");
        assertThat(flow2.getSourceRef()).isEqualTo("xor1");
        assertThat(flow2.getTargetRef()).isEqualTo("userTask1");

        BpmnFlowModel flow3 = bpmn.getFlow("flow3");
        assertThat(flow3.getFlowId()).isEqualTo("flow3");
        assertThat(flow3.getSourceRef()).isEqualTo("xor1");
        assertThat(flow3.getTargetRef()).isEqualTo("userTask2");
        assertThat(flow3.getConditionExpression()).isNotNull();
        assertThat(flow3.getConditionExpression().getExpression()).isEqualTo("=a > 5");
        assertThat(flow3.getConditionExpression().getType()).isEqualTo("bpmn:tFormalExpression");

        BpmnFlowModel flow4 = bpmn.getFlow("flow4");
        assertThat(flow4.getFlowId()).isEqualTo("flow4");
        assertThat(flow4.getSourceRef()).isEqualTo("userTask1");
        assertThat(flow4.getTargetRef()).isEqualTo("xor2");

        BpmnFlowModel flow5 = bpmn.getFlow("flow5");
        assertThat(flow5.getFlowId()).isEqualTo("flow5");
        assertThat(flow5.getSourceRef()).isEqualTo("userTask2");
        assertThat(flow5.getTargetRef()).isEqualTo("xor2");

        BpmnFlowModel flow6 = bpmn.getFlow("flow6");
        assertThat(flow6.getFlowId()).isEqualTo("flow6");
        assertThat(flow6.getSourceRef()).isEqualTo("xor2");
        assertThat(flow6.getTargetRef()).isEqualTo("endEvent");
    }

    @Test
    void testParse8() throws IOException {
        String bpmnFile = "src/test/files/test8.bpmn";

        String bpmnStr = Files.readString(Path.of(bpmnFile));

        BpmnParseService service = new BpmnParseServiceImpl();
        BpmnProcessDefinitionModel bpmn = service.parse(bpmnStr);

        assertThat(bpmn).isNotNull();
        assertThat(bpmn.getName()).isEqualTo("Test8 Process");
        assertThat(bpmn.getKey()).isEqualTo("call-activity-process");
        assertThat(bpmn.getElements()).isNotNull().hasSize(3);
        assertThat(bpmn.getStartEvent()).isNotNull();
        assertThat(bpmn.getFlows()).isNotNull().hasSize(2);

        BpmnElementModel startEvent = bpmn.getElement("startEvent1");
        assertThat(startEvent.getId()).isEqualTo("startEvent1");
        assertThat(startEvent.getName()).isEqualTo("Start Event 1");
        assertThat(startEvent.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(startEvent.getOutgoing()).isNotNull().hasSize(1);
        assertThat(startEvent.getOutgoing().get(0)).isEqualTo("flow1");
        assertThat(startEvent.getIncoming()).isNotNull().hasSize(0);

        BpmnElementModel endEvent = bpmn.getElement("endEvent1");
        assertThat(endEvent.getId()).isEqualTo("endEvent1");
        assertThat(endEvent.getName()).isEqualTo("End Event 1");
        assertThat(endEvent.getType()).isEqualTo(BpmnElementType.END_EVENT);
        assertThat(endEvent.getOutgoing()).isNotNull().hasSize(0);
        assertThat(endEvent.getIncoming()).isNotNull().hasSize(1);
        assertThat(endEvent.getIncoming().get(0)).isEqualTo("flow2");

        BpmnElementModel callActivity = bpmn.getElement("callActivity1");
        assertThat(callActivity.getId()).isEqualTo("callActivity1");
        assertThat(callActivity.getName()).isEqualTo("Call Activity 1");
        assertThat(callActivity.getType()).isEqualTo(BpmnElementType.CALL_ACTIVITY);
        assertThat(callActivity.getOutgoing()).isNotNull().hasSize(1);
        assertThat(callActivity.getIncoming()).isNotNull().hasSize(1);
        assertThat(callActivity.getIncoming().get(0)).isEqualTo("flow1");
        assertThat(callActivity.getOutgoing().get(0)).isEqualTo("flow2");
        assertThat(callActivity.getExtensions()).isNotNull();
        assertThat(callActivity.getExtensions().getCallActivityExtension()).isNotNull();
        assertThat(callActivity.getExtensions().getCallActivityExtension().getProcessId()).isEqualTo("dummy-process");
        assertThat(callActivity.getExtensions().getCallActivityExtension().getBindingType()).isEqualTo("deployment");
        assertThat(callActivity.getExtensions().getCallActivityExtension().getPropagateAllChildVariables()).isFalse();

        BpmnFlowModel flow5 = bpmn.getFlow("flow1");
        assertThat(flow5.getFlowId()).isEqualTo("flow1");
        assertThat(flow5.getSourceRef()).isEqualTo("startEvent1");
        assertThat(flow5.getTargetRef()).isEqualTo("callActivity1");

        BpmnFlowModel flow6 = bpmn.getFlow("flow2");
        assertThat(flow6.getFlowId()).isEqualTo("flow2");
        assertThat(flow6.getSourceRef()).isEqualTo("callActivity1");
        assertThat(flow6.getTargetRef()).isEqualTo("endEvent1");
    }
}
