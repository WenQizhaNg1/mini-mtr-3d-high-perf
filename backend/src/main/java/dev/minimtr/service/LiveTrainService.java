package dev.minimtr.service;

import dev.minimtr.model.vo.TrainSnapshotVo;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@Order(20)
public class LiveTrainService implements ApplicationRunner, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LiveTrainService.class);
    private final MotionService motion;
    private final RealtimeService realtime;
    private final boolean enabled;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile TrainSnapshotVo latest;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public LiveTrainService(MotionService motion, RealtimeService realtime,
            @Value("${live.enabled:true}") boolean enabled,
            @Value("${migration.import-legacy:false}") boolean importing) {
        this.motion = motion; this.realtime = realtime; this.enabled = enabled && !importing;
    }

    @Override public boolean isAutoStartup() { return false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void run(ApplicationArguments args) { start(); }

    @Override
    public synchronized void start() {
        if (!enabled || running) return;
        try {
            motion.start();
            latest = motion.snapshot();
            running = true;
            executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("live-motion").factory());
            executor.scheduleWithFixedDelay(this::update, 1, 1, TimeUnit.SECONDS);
            realtime.start();
        } catch (Exception error) {
            running = false; latest = null;
            if (executor != null) executor.shutdownNow();
            try { realtime.stop(); } catch (Exception stopError) { error.addSuppressed(stopError); }
            try { motion.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
            throw new IllegalStateException("Cannot start live motion", error);
        }
    }

    private void update() {
        try {
            var snapshot = motion.snapshot();
            if (!running) return;
            latest = snapshot;
            subscriptions.forEach(s -> s.publish(snapshot));
        } catch (Exception error) {
            latest = null;
            subscriptions.forEach(Subscription::close);
            log.error("Live snapshot failed; withholding stale snapshots", error);
        }
    }

    public TrainSnapshotVo latest() { return latest; }

    public synchronized SseEmitter subscribe() {
        if (!running || latest == null) return null;
        // Periodic reconnect bounds abandoned connections; the client receives retry: 3000.
        var emitter = new SseEmitter(60_000L);
        var subscription = new Subscription(latest);
        subscriptions.add(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        subscription.sender = Thread.ofVirtual().name("train-sse").start(() -> {
            try {
                emitter.send(SseEmitter.event().reconnectTime(3000));
                TrainSnapshotVo snapshot;
                while ((snapshot = subscription.next()) != null) emitter.send(SseEmitter.event().data(snapshot));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException error) {
                log.debug("Train stream disconnected", error);
            } catch (RuntimeException error) {
                log.error("Train stream failed", error);
            } finally {
                subscription.close(); emitter.complete();
            }
        });
        return emitter;
    }

    // A slow consumer retains only the newest snapshot. Socket writes never run on the motion thread.
    final class Subscription {
        private TrainSnapshotVo pending;
        private boolean closed;
        private volatile Thread sender;
        Subscription(TrainSnapshotVo initial) { pending = initial; }
        synchronized void publish(TrainSnapshotVo value) { if (!closed) { pending = value; notifyAll(); } }
        synchronized TrainSnapshotVo next() throws InterruptedException {
            while (!closed && pending == null) wait();
            if (closed) return null;
            var value = pending; pending = null; return value;
        }
        synchronized void close() {
            if (closed) return;
            closed = true; pending = null; notifyAll(); subscriptions.remove(this);
            if (sender != null && sender != Thread.currentThread()) sender.interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false; latest = null;
        subscriptions.forEach(Subscription::close);
        executor.shutdownNow();
        try {
            realtime.stop();
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Live snapshot worker did not stop");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); log.error("Interrupted stopping live workers", error);
        } finally {
            try { motion.close(); } catch (Exception error) { log.error("Failed to close live motion", error); }
        }
    }
}
