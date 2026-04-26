package com.zorrodev.bpm.engine.bpmn.model;

import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnElementExtensionModel {
    private ServiceTaskExtensionModel serviceTaskExtension;
    private UserTaskExtensionModel userTaskExtension;
    private ExclusiveGatewayExtensionModel exclusiveGatewayExtension;
    private TimerEventExtensionModel timerEventExtension;
    private MessageEventExtensionModel messageEventExtension;
    private CallActivityExtensionModel callActivityExtension;
}
