package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * A utility class that submits tasks to an ExecutorService. Tasks sharing one or more keys
 * will be executed serially in submission order, i.e. a running task takes an exclusive lock
 * on the resources guarded by the provided keys.
 */
public class SerialByKeyExecutor {

    private static final Logger logger = LogManager.getLogger(SerialByKeyExecutor.class);

    private final ExecutorService executor;

    /**
     * Maps task key to the last Future submitted with that key.
     */
    private final ConcurrentHashMap<String, CompletableFuture<?>> activeFutures = new ConcurrentHashMap<>();
    private final Object submissionLock = new Object();

    /**
     * Creates a new SerialByKeyExecutor that will use the given ExecutorService to run tasks.
     *
     * @param executor the ExecutorService to use
     */
    public SerialByKeyExecutor(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    /**
     * Submits a task for execution. Tasks sharing one or more keys will be executed serially in submission order.
     *
     * @param keys the keys to associate with the task
     * @param task the task to execute
     * @param <T> the type of the task's result
     * @return a CompletableFuture representing the pending completion of the task
     */
    public <T> CompletableFuture<T> submit(Set<String> keys, Callable<T> task) {
        var taskKeys = Set.copyOf(keys);
        if (taskKeys.isEmpty()) {
            throw new IllegalArgumentException("keys must not be empty");
        }

        Supplier<T> supplier = () -> {
            try {
                return task.call();
            } catch (Exception e) {
                logger.error("Task for keys '{}' failed", String.join(",", taskKeys), e);
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e);
            }
        };

        CompletableFuture<T> taskFuture;
        synchronized (submissionLock) {
            var prerequisiteFutures = taskKeys.stream()
                                          .map(activeFutures::get)
                                          .filter(Objects::nonNull)
                                          .distinct()
                                          .toList();

            Supplier<CompletableFuture<T>> taskRunner = () -> CompletableFuture.supplyAsync(supplier, executor);

            if (prerequisiteFutures.isEmpty()) {
                taskFuture = taskRunner.get();
            } else {
                var allPrerequisites = CompletableFuture.allOf(prerequisiteFutures.toArray(new CompletableFuture[0]));
                taskFuture = allPrerequisites.handle((r, e) -> null) // ignore errors from prerequisites
                                             .thenComposeAsync(ignored -> taskRunner.get(), executor);
            }

            for (var key : taskKeys) {
                activeFutures.put(key, taskFuture);
            }

            taskFuture.whenComplete((res, err) -> {
                for (var key : taskKeys) {
                    activeFutures.remove(key, taskFuture);
                }
            });
        }

        return taskFuture;
    }

    /**
     * Submits a task for execution. Tasks with the same key will be executed in the order they were submitted.
     *
     * @param key the key to associate with the task
     * @param task the task to execute
     * @param <T> the type of the task's result
     * @return a CompletableFuture representing the pending completion of the task
     */
    public <T> CompletableFuture<T> submit(String key, Callable<T> task) {
        return submit(Set.of(key), task);
    }

    /**
     * Submits a task with no return value for execution.
     *
     * @param key the key to associate with the task
     * @param task the task to execute
     * @return a CompletableFuture representing the pending completion of the task
     */
    public CompletableFuture<Void> submit(String key, Runnable task) {
        return submit(key, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Submits a task with no return value for execution. Tasks sharing one or more keys will be executed serially.
     *
     * @param keys the keys to associate with the task
     * @param task the task to execute
     * @return a CompletableFuture representing the pending completion of the task
     */
    public CompletableFuture<Void> submit(Set<String> keys, Runnable task) {
        return submit(keys, () -> {
            task.run();
            return null;
        });
    }


    /**
     * @return the number of keys with active tasks.
     */
    public int getActiveKeyCount() {
        return activeFutures.size();
    }
}
