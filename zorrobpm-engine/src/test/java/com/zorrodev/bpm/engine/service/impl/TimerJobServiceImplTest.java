package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimerJobServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @Mock
    private DBService dbService;

    @Mock
    private ActivityService activityService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TimerJobServiceImpl service() {
        return new TimerJobServiceImpl(dbService, activityService, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager, 50);
    }

    @Test
    void readsDueTimersWithTheBatchSizeAndCountsFired() {
        Timer first = timer();
        Timer skipped = timer();
        when(dbService.findDueTimers(NOW, 50)).thenReturn(List.of(first, skipped));
        when(activityService.fireTimer(first.getId(), first.getActivityId())).thenReturn(true);
        when(activityService.fireTimer(skipped.getId(), skipped.getActivityId())).thenReturn(false);

        assertThat(service().fireDueTimers()).isEqualTo(1);
    }

    @Test
    void failureOfOneTimerDoesNotStopTheOthers() {
        Timer failing = timer();
        Timer next = timer();
        when(dbService.findDueTimers(NOW, 50)).thenReturn(List.of(failing, next));
        when(activityService.fireTimer(failing.getId(), failing.getActivityId())).thenThrow(new CannotAcquireLockException("deadlock"));
        when(activityService.fireTimer(next.getId(), next.getActivityId())).thenReturn(true);

        assertThat(service().fireDueTimers()).isEqualTo(1);

        verify(activityService).fireTimer(next.getId(), next.getActivityId());
        // Each timer has its own transaction: the failed one is rolled back, the other committed.
        verify(transactionManager).rollback(any());
        verify(transactionManager, times(2)).commit(any());
    }

    private static Timer timer() {
        Timer timer = new Timer();
        timer.setId(UUID.randomUUID());
        timer.setActivityId(UUID.randomUUID());
        timer.setBpmnElementId("timeout");
        timer.setDueAt(NOW);
        return timer;
    }
}
