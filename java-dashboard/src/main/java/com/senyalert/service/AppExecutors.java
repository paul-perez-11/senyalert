package com.senyalert.service;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the bounded background workers used by persistence, files, and playback. */
public final class AppExecutors implements AutoCloseable {
    private final ExecutorService database = Executors.newSingleThreadExecutor(namedFactory("senyalert-db"));
    private final ExecutorService media = Executors.newFixedThreadPool(2, namedFactory("senyalert-media"));

    public ExecutorService database() {
        return database;
    }

    public ExecutorService media() {
        return media;
    }

    @Override
    public void close() {
        shutdown(database);
        shutdown(media);
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
