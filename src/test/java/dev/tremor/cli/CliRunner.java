package dev.tremor.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

/** Test helper that runs the CLI in-process and captures output. */
record CliRunner(int exitCode, String out, String err) {

    static CliRunner run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = TremorCommand.newCommandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute(args);
        return new CliRunner(code, out.toString(), err.toString());
    }
}
