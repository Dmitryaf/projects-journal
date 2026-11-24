package io.github.dmitryaf.projectsjournal;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class ProjectRefresh implements AutoCloseable {

    private final ProjectService projects;
    private final JournalSettings settings;
    private final JsonMapper mapper;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("project-refresh").factory());

    private volatile long generation;
    private ScheduledFuture<?> timer;
    private boolean closed;
    private boolean running;
    private int interval;
    private String lastAttemptAt;
    private List<String> errors = List.of();

    ProjectRefresh(ProjectService projects, JournalSettings settings, JsonMapper mapper) {
        this.projects = projects;
        this.settings = settings;
        this.mapper = mapper;
        executor.setRemoveOnCancelPolicy(true);
    }

    void start() throws SQLException {
        projects.scan();
        reschedule();
    }

    synchronized void reschedule() throws SQLException {
        interval = settings.read().get("autoRefreshMinutes").intValue();
        generation++;
        if (timer != null) {
            timer.cancel(false);
        }
        if (!running) {
            schedule();
        }
    }

    synchronized ObjectNode info() {
        var result = mapper.createObjectNode().put("running", running).put("lastAttemptAt", lastAttemptAt);
        var errorList = result.putArray("errors");
        errors.forEach(errorList::add);
        return result;
    }

    private void schedule() {
        if (!closed && interval > 0) {
            long scheduledGeneration = generation;
            timer = executor.schedule(() -> run(scheduledGeneration), interval, TimeUnit.MINUTES);
        }
    }

    private void run(long scheduledGeneration) {
        synchronized (this) {
            if (closed || scheduledGeneration != generation) {
                return;
            }
            running = true;
            lastAttemptAt = new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now());
            errors = List.of();
        }
        List<String> result;
        try {
            result = projects.refreshKnown(() -> scheduledGeneration != generation
                    || Thread.currentThread().isInterrupted());
        } catch (SQLException | RuntimeException failure) {
            result = List.of("Не удалось обновить проекты. Следующая попытка будет выполнена автоматически.");
        }
        synchronized (this) {
            errors = List.copyOf(result);
            running = false;
            schedule();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            generation++;
            if (timer != null) {
                timer.cancel(false);
            }
            executor.shutdownNow();
        }
        try {
            executor.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
