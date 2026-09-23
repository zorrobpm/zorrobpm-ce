package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ServiceTaskFailed {
    private UUID serviceTaskId;
    private String message;
}
