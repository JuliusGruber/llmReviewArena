package dev.reviewarena.cli;

import dev.reviewarena.agent.AgentException;
import dev.reviewarena.config.ConfigException;
import dev.reviewarena.git.GitValidationException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Maps domain exceptions to CLI exit codes.
 */
public class ExceptionHandler implements IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
        if (ex instanceof GitValidationException gve) {
            cmd.getErr().println(gve.getMessage());
            return gve.getExitCode();
        }
        if (ex instanceof ConfigException ce) {
            cmd.getErr().println(ce.getMessage());
            return 5;
        }
        if (ex instanceof AgentException ae) {
            cmd.getErr().println(ae.getMessage());
            return 4;
        }

        cmd.getErr().println("Error: " + ex.getMessage());
        return 1;
    }
}
