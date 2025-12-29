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

    private final ArenaConfig config;
    private final WorkspaceManager workspace;
    private final CommandBuilder commandBuilder;
    private final OutputValidator outputValidator;

    public AgentExecutor(ArenaConfig config, WorkspaceManager workspace) {
        this.config = config;
        this.workspace = workspace;
        this.commandBuilder = new CommandBuilder();
        this.outputValidator = new OutputValidator(config.maxOutputSizeKb());
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

        List<String> command = commandBuilder.build(agentConfig, promptFile, outputFile);

        AgentProcess process = AgentProcess.builder()
            .agentName(agentConfig.name())
            .round(round)
            .command(command)
            .workingDir(workspace.getArenaDir().getParent()) // project root
            .outputFile(outputFile)
            .promptFile(promptFile)  // Redirect stdin from prompt file
            .stdoutLog(agentDir.resolve("stdout.log"))
            .stderrLog(agentDir.resolve("stderr.log"))
            .timeoutMs(config.agentTimeoutMs())
            .gracePeriodMs(config.gracePeriodMs())
            .outputValidator(outputValidator)
            .build();

        return process.execute();
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
}
