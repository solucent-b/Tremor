package dev.tremor.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/** Supplies the version string from the build-filtered {@code tremor.properties}. */
public final class TremorVersion implements IVersionProvider {

    private static final String RESOURCE = "/dev/tremor/tremor.properties";

    public static String current() {
        try (InputStream in = TremorVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String[] getVersion() {
        return new String[] {"tremor " + current()};
    }
}
