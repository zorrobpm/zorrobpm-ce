package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Candidates are read without locks; each one is then fired in a transaction of its own, which
 * takes the host's row lock with SKIP LOCKED. Several engine nodes may pick the same candidates,
 * but only the one holding the host lock fires it, and the others then see it fired or canceled.
 */
@Slf4j
@Service
public class TimerJobServiceImpl implements TimerJobService {

    private final DBService dbService;
    private final ActivityService activityService;
    private final Clock clock;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate fireTransaction;
    private final int batchSize;

    public TimerJobServiceImpl(DBService dbService, ActivityService activityService, Clock clock,
                               PlatformTransactionManager transactionManager,
                               @Value("${zorrobpm.timers.batch-size:100}") int batchSize) {
        this.dbService = dbService;
        this.activityService = activityService;
        this.clock = clock;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.fireTransaction = new TransactionTemplate(transactionManager);
        this.fireTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = batchSize;
    }

    @Override
    public int fireDueTimers() {
        List<Timer> due = readTransaction.execute(status -> dbService.findDueTimers(clock.instant(), batchSize));
        int fired = 0;
        for (Timer timer : due) {
            try {
                if (Boolean.TRUE.equals(fireTransaction.execute(status -> activityService.fireTimer(timer.getId(), timer.getActivityId())))) {
                    fired++;
                }
            } catch (RuntimeException e) {
                // Rolled back: the timer stays scheduled and is retried on the next poll.
                log.error("Timer {} of {} on {} failed to fire", timer.getId(), timer.getBpmnElementId(), timer.getActivityId(), e);
            }
        }
        return fired;
    }
}
