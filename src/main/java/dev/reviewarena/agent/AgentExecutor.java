package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import dev.reviewarena.config.ArenaConfig;
import dev.reviewarena.io.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Executes agents for tournament rounds with concurrency control.
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /**
     * Round value used for synthesis execution.
     * Synthesis is not a competitive round, so we use 0 to indicate it's outside
     * the normal round sequence. The agent name suffix "-synthesis" distinguishes it.
     */
    private static final int SYNTHESIS_ROUND = 0;

    private final ArenaConfig config;
    private final WorkspaceManager workspace;
    private final CommandBuilder commandBuilder;
    private final OutputValidator outputValidator;
    private final DockerChecker dockerChecker;

    /**
     * Production constructor.
     * Uses DockerAvailabilityChecker for Docker validation.
     */
    public AgentExecutor(ArenaConfig config, WorkspaceManager workspace) {
        this(config, workspace, new DockerAvailabilityChecker());
    }

    /**
     * Package-private constructor for testing.
     * Allows mock DockerChecker injection.
     */
    AgentExecutor(ArenaConfig config, WorkspaceManager workspace, DockerChecker dockerChecker) {
        this.config = config;
        this.workspace = workspace;
        this.commandBuilder = new CommandBuilder();
        this.outputValidator = new OutputValidator(config.maxOutputSizeKb());
        this.dockerChecker = dockerChecker;

        // Validate Docker availability if any agent uses it
        validateDockerIfNeeded();
    }

    /**
     * Validates Docker availability if any agent requires Docker.
     * Called once at construction time to fail fast if Docker is required but unavailable.
     *
     * <p>Docker is required in these scenarios:
     * <ul>
     *   <li>Any enabled agent has Docker configured</li>
     *   <li>Claude (synthesizer) is disabled but has Docker enabled for synthesis</li>
     * </ul>
     */
    private void validateDockerIfNeeded() {
        // Check if any enabled agent uses Docker
        boolean anyEnabledAgentUsesDocker = config.agents().values().stream()
            .filter(AgentConfig::enabled)
            .anyMatch(a -> a.docker().enabled());

        // Check if Claude (synthesizer) is disabled but has Docker enabled for synthesis
        AgentConfig claude = config.agents().get("claude");
        boolean claudeSynthesisNeedsDocker = claude != null
            && !claude.enabled()
            && claude.docker().enabled();

        if (anyEnabledAgentUsesDocker || claudeSynthesisNeedsDocker) {
            dockerChecker.requireDocker();
            log.info("Docker mode enabled for container-based agent execution");
        }
    }

    /**
     * Executes all enabled agents for a given round.
     *
     * @param round the round number (0-indexed)
     * @return map of agent name to execution result
     * @throws AgentException if round execution fails catastrophically
     */
    public Map<String, AgentResult> executeRound(int round) {
        List<AgentConfig> enabledAgents = getEnabledAgents();

        if (enabledAgents.isEmpty()) {
            log.warn("No enabled agents to execute for round {}", round);
            return Map.of();
        }

        if (round == 0) {
            log.info("Starting initial round with {} agents: {}",
                enabledAgents.size(),
                enabledAgents.stream().map(AgentConfig::name).toList());
        } else {
            log.info("Starting round {}/{} with {} agents: {}",
                round, config.maxRounds(), enabledAgents.size(),
                enabledAgents.stream().map(AgentConfig::name).toList());
        }

        return executeAgents(enabledAgents, round);
    }

    /**
     * Executes specific agents for a given round.
     *
     * @param round the round number (0-indexed)
     * @param agentNames set of agent names to execute (must be enabled in config)
     * @return map of agent name to execution result
     * @throws AgentException if round execution fails catastrophically
     */
    public Map<String, AgentResult> executeRound(int round, Set<String> agentNames) {
        List<AgentConfig> agents = config.agents().values().stream()
            .filter(AgentConfig::enabled)
            .filter(a -> agentNames.contains(a.name()))
            .sorted(Comparator.comparing(AgentConfig::name))
            .toList();

        if (agents.isEmpty()) {
            log.warn("No matching agents to execute for round {}", round);
            return Map.of();
        }

        if (round == 0) {
            log.info("Starting initial round with {} agents: {}",
                agents.size(),
                agents.stream().map(AgentConfig::name).toList());
        } else {
            log.info("Starting round {}/{} with {} agents: {}",
                round, config.maxRounds(), agents.size(),
                agents.stream().map(AgentConfig::name).toList());
        }

        return executeAgents(agents, round);
    }

    /**
     * Executes the given agents for a round.
     *
     * @param agents the agents to execute
     * @param round the round number
     * @return map of agent name to execution result
     */
    private Map<String, AgentResult> executeAgents(List<AgentConfig> agents, int round) {
        // Concurrency control: 0 = unlimited, else use semaphore
        Semaphore semaphore = config.maxConcurrent() > 0
            ? new Semaphore(config.maxConcurrent())
            : null;

        ConcurrentHashMap<String, AgentResult> results = new ConcurrentHashMap<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (AgentConfig agent : agents) {
                Future<?> future = executor.submit(() -> {
                    if (semaphore != null) {
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    try {
                        AgentResult result = executeAgent(agent, round);
                        results.put(agent.name(), result);
                        logResult(result);
                    } finally {
                        if (semaphore != null) {
                            semaphore.release();
                        }
                    }
                });
                futures.add(future);
            }

            // Wait for all agents with round timeout
            waitForAllWithTimeout(futures, config.roundTimeoutMs());

        } catch (Exception e) {
            log.error("Round {} execution failed: {}", round, e.getMessage());
            throw new AgentException("Round execution failed: " + e.getMessage(), e);
        }

        int successes = (int) results.values().stream().filter(AgentResult::isSuccess).count();
        log.info("Round {} complete: {}/{} agents succeeded", round, successes, agents.size());

        return Map.copyOf(results);
    }

    private AgentResult executeAgent(AgentConfig agentConfig, int round) {
        Path promptFile = workspace.getRoundPromptPath(round, agentConfig.name());
        Path agentDir = workspace.getAgentDir(round, agentConfig.name());
        Path outputFile = agentDir.resolve("review.md");
        Path projectRoot = workspace.getArenaDir().getParent();

        // CommandBuilder uses HOST paths - path translation happens in AgentProcess.resolveCommand()
        List<String> command = commandBuilder.build(agentConfig, promptFile, outputFile);

        // Use try-with-resources to ensure process resources are cleaned up.
        // AgentProcess.execute() also calls close() in its finally block, but this provides
        // an extra safety net for any future code paths that might not call execute().
        try (AgentProcess process = AgentProcess.builder()
            .agentName(agentConfig.name())
            .agentType(agentConfig.type())
            .round(round)
            .command(command)           // HOST paths from CommandBuilder
            .workingDir(projectRoot)
            .outputFile(outputFile)     // HOST path for output validation
            .promptFile(promptFile)     // HOST path for stdin redirection
            .stdoutLog(agentDir.resolve("stdout.log"))
            .stderrLog(agentDir.resolve("stderr.log"))
            .timeoutMs(config.agentTimeoutMs())
            .gracePeriodMs(config.gracePeriodMs())
            .outputValidator(outputValidator)
            .showOutput(config.showAgentOutput())
            .dockerConfig(agentConfig.docker())  // Pass Docker config
            .build()) {

            return process.execute();
        }
    }

    private void waitForAllWithTimeout(List<Future<?>> futures, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        for (Future<?> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("Round timeout reached, cancelling remaining agents");
                futures.forEach(f -> f.cancel(true));
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Agent execution timed out during round");
                future.cancel(true);
            } catch (CancellationException e) {
                // Already cancelled
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                break;
            } catch (ExecutionException e) {
                log.error("Agent execution error: {}", e.getCause().getMessage());
            }
        }
    }

    private List<AgentConfig> getEnabledAgents() {
        return config.agents().values().stream()
            .filter(AgentConfig::enabled)
            .sorted(Comparator.comparing(AgentConfig::name)) // Alphabetical for determinism
            .toList();
    }

    private void logResult(AgentResult result) {
        switch (result.status()) {
            case SUCCESS -> log.info("Agent '{}' completed successfully in {}ms",
                result.agentName(), result.durationMs());
            case FAILED -> log.error("Agent '{}' failed in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
            case TIMEOUT -> log.error("Agent '{}' timed out in round {} after {}ms",
                result.agentName(), result.round(), result.durationMs());
            case INVALID_OUTPUT -> log.error("Agent '{}' produced invalid output in round {}: {}",
                result.agentName(), result.round(), result.failureReason());
        }
    }

    /**
     * Gets the names of agents that succeeded in the given results.
     */
    public static Set<String> getSuccessfulAgents(Map<String, AgentResult> results) {
        return results.entrySet().stream()
            .filter(e -> e.getValue().isSuccess())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Validates that Claude is available for synthesis.
     * Per spec: "Claude is always used for this step regardless of which agents participated."
     * Per spec: "If Claude did not participate in tournament rounds, it is still used for synthesis."
     *
     * <p>Claude is available for synthesis if it is configured (has a command).
     * The {@code enabled} flag only controls tournament participation, not synthesis availability.
     * Docker can be used as an alternative execution method if enabled.
     *
     * @throws AgentException if Claude is not configured
     */
    public void validateSynthesizerAvailable() {
        AgentConfig claude = config.agents().get("claude");
        if (claude == null) {
            throw new AgentException(
                "Final synthesis requires Claude CLI. Ensure 'claude' is configured in arena.yaml.");
        }
        // No additional validation needed - if claude is configured with a command,
        // it is assumed to be available for synthesis. The 'enabled' flag only controls
        // tournament participation, not availability for synthesis.
    }

    /**
     * Gets the synthesizer agent name.
     * Per spec: Claude is always used for synthesis.
     *
     * @return "claude" (the only valid synthesizer per spec)
     * @throws AgentException if Claude is not available
     */
    public String getSynthesizerAgent() {
        validateSynthesizerAvailable();
        return "claude";
    }

    /**
     * Executes the synthesis step using the specified agent.
     *
     * <p>Uses AgentProcess for consistent command resolution across platforms,
     * particularly for Windows where CLI tools need cmd.exe wrapping, and for
     * Docker where paths need translation to container paths.
     *
     * <p>The agent can be used for synthesis if either:
     * <ul>
     *   <li>The agent is enabled (local CLI available)</li>
     *   <li>Docker is enabled for the agent (container execution available)</li>
     * </ul>
     *
     * @param agentName  the name of the agent to use for synthesis (must be "claude")
     * @param promptPath the path to the synthesis prompt (HOST path)
     * @param outputPath the path where champion_review.md should be written (HOST path)
     * @return the synthesis result
     * @throws AgentException if agent is not found or not available
     */
    public SynthesisResult executeSynthesis(String agentName, Path promptPath, Path outputPath) {
        AgentConfig agentConfig = config.agents().get(agentName);
        if (agentConfig == null) {
            throw new AgentException("Synthesizer agent '" + agentName + "' not found");
        }
        // No 'enabled' check for synthesis - the 'enabled' flag only controls tournament
        // participation. Per spec: "If Claude did not participate in tournament rounds,
        // it is still used for synthesis."

        log.info("[SYNTHESIS] Starting synthesis with agent: {}", agentName);

        Path finalDir = workspace.getFinalDir();
        Path projectRoot = workspace.getArenaDir().getParent();

        // CommandBuilder uses HOST paths - same as executeAgent()
        List<String> command = commandBuilder.build(agentConfig, promptPath, outputPath);

        // Use try-with-resources to ensure process resources are cleaned up.
        // AgentProcess.execute() also calls close() in its finally block, but this provides
        // an extra safety net for any future code paths that might not call execute().
        try (AgentProcess process = AgentProcess.builder()
            .agentName(agentName + "-synthesis")
            .agentType(agentConfig.type())
            .round(SYNTHESIS_ROUND)
            .command(command)             // HOST paths from CommandBuilder
            .workingDir(projectRoot)
            .outputFile(outputPath)       // HOST path for output validation
            .promptFile(promptPath)       // HOST path for stdin redirection
            .stdoutLog(finalDir.resolve("synthesis-stdout.log"))
            .stderrLog(finalDir.resolve("synthesis-stderr.log"))
            .timeoutMs(config.agentTimeoutMs())
            .gracePeriodMs(config.gracePeriodMs())
            .outputValidator(outputValidator)
            .showOutput(config.showAgentOutput())
            .dockerConfig(agentConfig.docker())  // Pass Docker config
            .synthesis(true)                     // Mark as synthesis (not a competitive round)
            .build()) {

            AgentResult result = process.execute();

            // Convert AgentResult to SynthesisResult
            return switch (result.status()) {
                case SUCCESS -> {
                    log.info("[SYNTHESIS] Synthesis completed successfully in {}ms", result.durationMs());
                    yield SynthesisResult.success(agentName, result.durationMs(), outputPath);
                }
                case TIMEOUT -> {
                    log.error("[SYNTHESIS] Synthesis timed out after {}ms", result.durationMs());
                    yield SynthesisResult.timeout(agentName, result.durationMs());
                }
                case FAILED, INVALID_OUTPUT -> {
                    log.error("[SYNTHESIS] Synthesis failed: {}", result.failureReason());
                    yield SynthesisResult.failed(agentName, result.durationMs(), result.failureReason());
                }
            };
        }
    }
}
