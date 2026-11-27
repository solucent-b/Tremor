package dev.tremor;

import dev.tremor.cli.TremorCommand;

/** Entry point for the Tremor command line tool. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(TremorCommand.newCommandLine().execute(args));
    }
}
