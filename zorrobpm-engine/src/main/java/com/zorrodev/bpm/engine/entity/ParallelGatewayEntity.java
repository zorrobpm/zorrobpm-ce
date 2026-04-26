package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "parallel_gateways")
public class ParallelGatewayEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID enteredFlowId;
}
