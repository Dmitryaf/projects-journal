package io.github.dmitryaf.projectsjournal;

import java.util.ArrayList;
import java.util.List;

final class CodexCancellation implements AutoCloseable {

    private final List<Runnable> listeners = new ArrayList<>();
    private volatile boolean cancelled;

    boolean cancelled() {
        return cancelled;
    }

    void check() {
        if (cancelled) {
            throw CodexReaderTransport.unavailable();
        }
    }

    void cancel() {
        List<Runnable> actions;
        synchronized (listeners) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            actions = List.copyOf(listeners);
            listeners.clear();
        }
        actions.forEach(Runnable::run);
    }

    Registration onCancel(Runnable action) {
        synchronized (listeners) {
            if (!cancelled) {
                listeners.add(action);
                return () -> {
                    synchronized (listeners) {
                        listeners.remove(action);
                    }
                };
            }
        }
        action.run();
        return () -> { };
    }

    @Override
    public void close() {
        cancel();
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
