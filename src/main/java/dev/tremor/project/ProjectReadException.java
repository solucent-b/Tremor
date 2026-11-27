package dev.tremor.project;

/** The project's build file is missing or cannot be read. */
public class ProjectReadException extends RuntimeException {

    public ProjectReadException(String message) {
        super(message);
    }

    public ProjectReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
