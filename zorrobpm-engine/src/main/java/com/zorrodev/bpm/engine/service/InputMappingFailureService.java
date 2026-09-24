package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.engine.exception.InputMappingException;
import com.zorrodev.bpm.exchange.ErrorReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Turns a failed input mapping of an open service task into an incident on it. Used where the job is
 * built outside the entry of the element (after a commit, on a retry, on a resolve or on a gRPC
 * redelivery), so there is no element execution to catch the error: the failure is reported like a
 * worker failure without retries, in a transaction of its own.
 */
@Slf4j
@Service
public class InputMappingFailureService {

    public static final String ERROR_CODE = "INPUT_MAPPING_FAILED";

    private final ActivityService activityService;
    private final TransactionTemplate transaction;

    public InputMappingFailureService(@Lazy ActivityService activityService, PlatformTransactionManager transactionManager) {
        this.activityService = activityService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Opens an incident {@value #ERROR_CODE} on the service task; a closed or already failed task is left alone. */
    public void reportJobInputMappingFailure(UUID serviceTaskId, InputMappingException e) {
        log.warn("Input mapping of service task {} failed, no job is published: {}", serviceTaskId, e.getMessage());
        try {
            transaction.executeWithoutResult(status ->
                activityService.failServiceTask(serviceTaskId, ErrorReport.of(e, ERROR_CODE), new RetryOverride(0, null)));
        } catch (TaskNotActiveException | ServiceTaskNotFoundException late) {
            log.info("Input mapping failure of service task {} not recorded: {}", serviceTaskId, late.getMessage());
        } catch (RuntimeException failure) {
            log.error("Failed to record the input mapping failure of service task {}", serviceTaskId, failure);
        }
    }
}
