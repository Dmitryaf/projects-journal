package io.github.dmitryaf.projectsjournal;

public class TaskError extends RuntimeException {

    private final int status;

    public TaskError(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
