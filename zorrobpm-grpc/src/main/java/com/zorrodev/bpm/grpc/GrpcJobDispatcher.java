package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.JobDetailFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import com.zorrodev.bpm.exchange.grpc.Job;
import io.grpc.stub.ServerCallStreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes ready service task jobs to the open {@code SubscribeJobs} streams of this engine node.
 * <p>
 * The jobs live in the database: a job is pushed to one stream at a time and locked there for the
 * stream's lock timeout with a compare-and-set on the service task row, so that several engine nodes
 * on one database never hold the same job at once. A result, a closed stream or an expired lock makes
 * the job ready again (unless the result ended it).
 * <p>
 * Dispatching runs on one thread of its own: it is woken when a job is enqueued after a commit, when a
 * stream opens, gets ready or has a free slot, and every {@code zorrobpm.grpc.poll-interval} for the
 * jobs no event announced here (created without subscribers, before a restart, on another node, or
 * whose lock expired).
 */
@Slf4j
public class GrpcJobDispatcher implements DisposableBean {

    private final DBService dbService;
    private final JobDetailFactory jobDetailFactory;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final GrpcTransportProperties properties;

    /** Identifies this engine node in the owners of its locks. */
    private final String node = UUID.randomUUID().toString();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "zorrobpm-grpc-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean wakeUpPending = new AtomicBoolean();
    private int rotation;

    public GrpcJobDispatcher(DBService dbService, JobDetailFactory jobDetailFactory,
                             PlatformTransactionManager transactionManager, Clock clock,
                             GrpcTransportProperties properties) {
        this.dbService = dbService;
        this.jobDetailFactory = jobDetailFactory;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.properties = properties;
    }

    /** An open stream of jobs of one worker. */
    static final class Subscription {
        final String owner;
        final String worker;
        final Set<String> jobs;
        final Duration lockTimeout;
        final int maxActiveJobs;
        final ServerCallStreamObserver<Job> observer;
        /** Jobs pushed to this stream and still without a result, with the end of their lock. */
        final Map<UUID, Instant> active = new ConcurrentHashMap<>();
        /** Jobs whose lock expired on this stream: another stream gets them first. */
        final Set<UUID> expired = ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();

        Subscription(String owner, String worker, Set<String> jobs, Duration lockTimeout, int maxActiveJobs,
                     ServerCallStreamObserver<Job> observer) {
            this.owner = owner;
            this.worker = worker;
            this.jobs = jobs;
            this.lockTimeout = lockTimeout;
            this.maxActiveJobs = maxActiveJobs;
            this.observer = observer;
        }
    }

    /**
     * Opens a stream: jobs of the given types are pushed to it until the worker cancels it or the
     * engine stops.
     *
     * @param lockTimeout   null - {@code zorrobpm.grpc.lock-timeout}
     * @param maxActiveJobs null - {@code zorrobpm.grpc.max-active-jobs}
     */
    public void subscribe(String worker, Set<String> jobs, Duration lockTimeout, Integer maxActiveJobs,
                          ServerCallStreamObserver<Job> observer) {
        Subscription subscription = new Subscription(node + "/" + UUID.randomUUID(), worker, Set.copyOf(jobs),
            lockTimeout != null ? lockTimeout : properties.getLockTimeout(),
            maxActiveJobs != null ? maxActiveJobs : properties.getMaxActiveJobs(),
            observer);
        observer.setOnCancelHandler(() -> close(subscription, "cancelled by the worker"));
        observer.setOnReadyHandler(this::wakeUp);
        subscriptions.add(subscription);
        log.info("Worker {} subscribed to jobs {} (lock timeout {}, max active jobs {}) as {}",
            worker, subscription.jobs, subscription.lockTimeout, subscription.maxActiveJobs, subscription.owner);
        wakeUp();
    }

    /** A result for the job came in: the slot of the stream that got it is free again. */
    public void onResult(UUID serviceTaskId) {
        for (Subscription subscription : subscriptions) {
            subscription.active.remove(serviceTaskId);
            subscription.expired.remove(serviceTaskId);
        }
        wakeUp();
    }

    @EventListener
    public void on(ServiceTaskEnqueued event) {
        wakeUp();
    }

    @Scheduled(fixedDelayString = "${zorrobpm.grpc.poll-interval:1s}")
    public void poll() {
        wakeUp();
    }

    /** Runs a dispatch on the dispatcher thread; wake-ups that come while one is pending are merged. */
    void wakeUp() {
        if (wakeUpPending.compareAndSet(false, true)) {
            try {
                executor.execute(() -> {
                    wakeUpPending.set(false);
                    dispatch();
                });
            } catch (RuntimeException e) {
                // Rejected after shutdown: nothing to dispatch to any more.
                wakeUpPending.set(false);
            }
        }
    }

    /** Fills the free slots of the open streams with ready jobs. Runs on the dispatcher thread only. */
    void dispatch() {
        List<Subscription> order = new ArrayList<>(subscriptions);
        if (order.isEmpty()) {
            return;
        }
        // Round robin: the streams of one job type take turns at being first.
        Collections.rotate(order, -(rotation++ % order.size()));
        for (Subscription subscription : order) {
            try {
                fill(subscription);
            } catch (RuntimeException e) {
                // The jobs stay in the database and are pushed on a later wake-up.
                log.error("Failed to push jobs to {} ({})", subscription.owner, subscription.worker, e);
            }
        }
    }

    private void fill(Subscription subscription) {
        if (subscription.closed.get()) {
            return;
        }
        Instant now = clock.instant();
        subscription.active.entrySet().removeIf(entry -> {
            if (entry.getValue().isAfter(now)) {
                return false;
            }
            subscription.expired.add(entry.getKey());
            return true;
        });
        int free = subscription.maxActiveJobs - subscription.active.size();
        if (free <= 0 || !subscription.observer.isReady()) {
            return;
        }
        List<UUID> ready = transaction.execute(status -> dbService.findReadyServiceTaskJobs(subscription.jobs, free));
        for (UUID serviceTaskId : ready) {
            if (subscription.closed.get()) {
                return;
            }
            if (subscription.expired.contains(serviceTaskId) && hasOtherSubscriber(subscription)) {
                // The worker of this stream did not answer in time: give another worker a chance.
                continue;
            }
            Instant lockedUntil = clock.instant().plus(subscription.lockTimeout);
            JobDetailModel detail = transaction.execute(status ->
                dbService.lockServiceTaskJob(serviceTaskId, subscription.owner, lockedUntil) ? jobDetailFactory.create(serviceTaskId) : null);
            if (detail == null) {
                // Taken by another stream or node, or no longer ready.
                continue;
            }
            subscription.active.put(serviceTaskId, lockedUntil);
            for (Subscription other : subscriptions) {
                other.expired.remove(serviceTaskId);
            }
            try {
                subscription.observer.onNext(JobMessages.toJob(detail));
                log.info("Pushed job {} of service task {} to {} ({}), locked until {}",
                    detail.getJob(), serviceTaskId, subscription.owner, subscription.worker, lockedUntil);
            } catch (RuntimeException e) {
                log.warn("Failed to push job of service task {} to {} ({}): {}", serviceTaskId, subscription.owner, subscription.worker, e.toString());
                subscription.active.remove(serviceTaskId);
                releaseLock(serviceTaskId, subscription.owner);
                close(subscription, "push failed");
                return;
            }
        }
    }

    /** Whether another open stream of this node takes a job type of the given one. */
    private boolean hasOtherSubscriber(Subscription subscription) {
        for (Subscription other : subscriptions) {
            if (other != subscription && !other.closed.get() && !Collections.disjoint(other.jobs, subscription.jobs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the stream and the locks of its jobs without a result, so that they are pushed again at
     * once instead of after the lock timeout. The release runs on the dispatcher thread, after any
     * dispatch that may still be locking a job for this stream.
     */
    void close(Subscription subscription, String reason) {
        if (!subscription.closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.remove(subscription);
        log.info("Subscription {} of worker {} closed: {}", subscription.owner, subscription.worker, reason);
        try {
            executor.execute(() -> {
                releaseLocks(subscription.owner);
                dispatch();
            });
        } catch (RuntimeException e) {
            releaseLocks(subscription.owner);
        }
    }

    private void releaseLock(UUID serviceTaskId, String owner) {
        try {
            transaction.executeWithoutResult(status -> dbService.releaseServiceTaskLock(serviceTaskId, owner));
        } catch (RuntimeException e) {
            // The lock expires by itself.
            log.warn("Failed to release the lock of service task {} held by {}", serviceTaskId, owner, e);
        }
    }

    private void releaseLocks(String owner) {
        try {
            int released = transaction.execute(status -> dbService.releaseServiceTaskLocks(owner));
            if (released > 0) {
                log.info("Released {} job locks of {}", released, owner);
            }
        } catch (RuntimeException e) {
            // The locks expire by themselves.
            log.warn("Failed to release the job locks of {}", owner, e);
        }
    }

    /** Number of open streams on this node. */
    int subscriptionCount() {
        return subscriptions.size();
    }

    /** Ends the streams of this node and releases their locks, so that other nodes take the jobs at once. */
    @Override
    public void destroy() throws InterruptedException {
        List<Subscription> open = new ArrayList<>(subscriptions);
        executor.execute(() -> {
            for (Subscription subscription : open) {
                if (subscription.closed.compareAndSet(false, true)) {
                    subscriptions.remove(subscription);
                    try {
                        subscription.observer.onCompleted();
                    } catch (RuntimeException e) {
                        // Already cancelled.
                    }
                    releaseLocks(subscription.owner);
                }
            }
        });
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
