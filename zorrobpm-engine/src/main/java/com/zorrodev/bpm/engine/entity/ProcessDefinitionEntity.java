package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name="process_definitions")
public class ProcessDefinitionEntity {
    @Id
    private UUID id;
    @Column(name = "code")
    private String key;
    private String name;
    private Integer version;
    @Column(unique = true)
    private String sha256;
    private Instant createdAt;
    private String startFormKey;
}
