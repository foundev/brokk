package io.github.jbellis.brokk.difftool.scroll;

import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Pure debouncing utility for scroll operations that can be easily tested
 * without Swing dependencies. Handles timer management and debounce logic
 * in a thread-safe, testable way.
 */
public final class ScrollDebouncer {
    private final int debounceMs;
    private final AtomicReference<Timer> currentTimer = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();

    public ScrollDebouncer(int debounceMs) {
        this.debounceMs = debounceMs;
    }

    public record DebounceRequest<T>(T data, Consumer<T> action, @Nullable Runnable onComplete) {
        public DebounceRequest(T data, Consumer<T> action) {
            this(data, action, null);
        }
    }

    /**
     * Submits a request for debounced execution. If another request comes in
     * before the debounce period expires, the previous request is cancelled
     * and only the latest one will execute.
     */
    public <T> void submit(DebounceRequest<T> request) {
        lock.lock();
        try {
            // Cancel any existing timer
            var existing = currentTimer.get();
            if (existing != null) {
                existing.stop();
            }

            // Create new timer for this request
            var newTimer = new Timer(debounceMs, e -> {
                try {
                    request.action().accept(request.data());
                    var onComplete = request.onComplete();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                } finally {
                    // Clear the timer reference after execution
                    currentTimer.compareAndSet((Timer) e.getSource(), null);
                }
            });

            newTimer.setRepeats(false);
            currentTimer.set(newTimer);
            newTimer.start();
        } finally {
            lock.unlock();
        }
    }

    public void cancel() {
        lock.lock();
        try {
            var existing = currentTimer.getAndSet(null);
            if (existing != null) {
                existing.stop();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean hasPending() {
        var current = currentTimer.get();
        return current != null && current.isRunning();
    }

    /**
     * Stops any running timer and cleans up resources.
     * Should be called when the debouncer is no longer needed.
     */
    public void dispose() {
        cancel();
    }
}