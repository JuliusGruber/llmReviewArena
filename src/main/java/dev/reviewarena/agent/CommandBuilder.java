package dev.reviewarena.agent;

import dev.reviewarena.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds CLI commands for agent execution with flag translation.
 *
 * <p>Translates portable configuration flags to CLI-specific arguments:
 * <ul>
 *   <li>auto-approve: claude → --dangerously-skip-permissions, codex → --full-auto, gemini → --yolo</li>
 *   <li>allowed-tools: claude only → --allowedTools</li>
 * </ul>
 *
 * <p>Supports placeholders in command templates:
 * <ul>
 *   <li>{@code @prompt.md} → replaced with absolute path to prompt file</li>
 *   <li>{@code @output} → replaced with absolute path to output file (review.md)</li>
 * </ul>
 */
public class CommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(CommandBuilder.class);
    private static final String PROMPT_PLACEHOLDER = "@prompt.md";
    private static final String OUTPUT_PLACEHOLDER = "@output";

    /**
     * Builds the complete command for spawning an agent.
     *
     * @param agentConfig the agent configuration
     * @param promptFile  absolute path to the prompt file
     * @param outputFile  absolute path to the agent's output file (review.md)
     * @return immutable list of command arguments
     */
    public List<String> build(AgentConfig agentConfig, Path promptFile, Path outputFile) {
        List<String> command = new ArrayList<>(agentConfig.command());

        // Replace placeholders with actual paths
        replacePlaceholders(command, promptFile, outputFile);

        // Add translated flags based on agent name
        addTranslatedFlags(command, agentConfig);

        log.debug("Built command for {}: {}", agentConfig.name(), command);

        return List.copyOf(command);
    }

    private void replacePlaceholders(List<String> command, Path promptFile, Path outputFile) {
        String promptPath = promptFile.toAbsolutePath().toString();
        String outputPath = outputFile.toAbsolutePath().toString();

        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.contains(PROMPT_PLACEHOLDER)) {
                // Use forward slashes for shell compatibility (bash on Windows)
                String safePath = isShellCommand(command) ? toForwardSlashes(promptPath) : promptPath;
                arg = arg.replace(PROMPT_PLACEHOLDER, safePath);
            }
            if (arg.contains(OUTPUT_PLACEHOLDER)) {
                String safePath = isShellCommand(command) ? toForwardSlashes(outputPath) : outputPath;
                arg = arg.replace(OUTPUT_PLACEHOLDER, safePath);
            }
            command.set(i, arg);
        }
    }

    /**
     * Checks if the command is a shell command (bash, sh, cmd).
     */
    private boolean isShellCommand(List<String> command) {
        if (command.isEmpty()) return false;
        String first = command.get(0).toLowerCase();
        return first.equals("bash") || first.equals("sh") || first.contains("bash") || first.contains("sh");
    }

    /**
     * Converts Windows backslashes to forward slashes for shell compatibility.
     */
    private String toForwardSlashes(String path) {
        return path.replace('\\', '/');
    }

    private void addTranslatedFlags(List<String> command, AgentConfig config) {
        Map<String, Object> flags = config.flags();
        String agentName = config.name().toLowerCase();

        // Translate auto-approve flag
        if (Boolean.TRUE.equals(flags.get("auto-approve"))) {
            String autoApproveFlag = switch (agentName) {
                case "claude" -> "--dangerously-skip-permissions";
                case "codex" -> "--full-auto";
                case "gemini" -> "--yolo";
                default -> null;
            };
            if (autoApproveFlag != null && !command.contains(autoApproveFlag)) {
                command.add(autoApproveFlag);
            } else if (autoApproveFlag == null) {
                log.warn("Unknown agent '{}' has auto-approve enabled but no flag translation available. " +
                         "Agent will run without auto-approve flag.", agentName);
            }
        }

        // Translate allowed-tools flag (Claude only)
        if ("claude".equals(agentName) && flags.containsKey("allowed-tools")) {
            Object allowedTools = flags.get("allowed-tools");
            if (allowedTools instanceof List<?> toolList && !toolList.isEmpty()) {
                String toolsCsv = toolList.stream()
                    .map(Object::toString)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
                if (!toolsCsv.isEmpty()) {
                    command.add("--allowedTools");
                    command.add(toolsCsv);
                }
            }
        }
    }
}
