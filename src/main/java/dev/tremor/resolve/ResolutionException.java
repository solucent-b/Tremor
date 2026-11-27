package dev.tremor.resolve;

/** A POM, jar or dependency tree could not be fetched from the configured repositories. */
public class ResolutionException extends RuntimeException {

    public ResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
