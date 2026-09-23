package com.zorrodev.bpm.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * What a reported service task failure led to: an open incident ({@code id}), or a retry scheduled
 * instead of it ({@code id} empty, {@code nextRetryAt} set).
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ServiceTaskFailureDTO {
    /** The open incident; null while a retry is scheduled. */
    private UUID id;
    /** Retries still available. */
    private int retries;
    /** Due time of the scheduled retry; null when there is none. */
    private Instant nextRetryAt;
}
