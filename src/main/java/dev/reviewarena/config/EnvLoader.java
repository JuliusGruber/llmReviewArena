package dev.reviewarena.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads environment variables from .env file and provides unified access.
 *
 * <p>This utility checks environment variables in the following order:
 * <ol>
 *   <li>System environment (System.getenv) - takes precedence</li>
 *   <li>.env file in the current directory</li>
 * </ol>
 *
 * <p>This allows users to either:
 * <ul>
 *   <li>Set environment variables directly in their shell</li>
 *   <li>Use a .env file for convenience (no need to source manually)</li>
 * </ul>
 */
public class EnvLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvLoader.class);

    private static Dotenv dotenv;
    private static boolean initialized = false;

    private EnvLoader() {
        // Utility class
    }

    /**
     * Initializes the environment loader by reading the .env file.
     *
     * <p>Should be called once at application startup. If the .env file
     * doesn't exist, the loader will still work but only return system
     * environment variables.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            dotenv = Dotenv.configure()
                .ignoreIfMissing()  // Don't fail if .env doesn't exist
                .load();
            log.debug("Environment loader initialized, .env file loaded if present");
        } catch (DotenvException e) {
            log.warn("Failed to load .env file: {}. Using system environment only.", e.getMessage());
            dotenv = null;
        }

        initialized = true;
    }

    /**
     * Gets an environment variable value.
     *
     * <p>Checks System.getenv() first (real environment takes precedence),
     * then falls back to the .env file value.
     *
     * @param key the environment variable name
     * @return the value, or null if not found in either source
     */
    public static String getEnv(String key) {
        // System environment always takes precedence
        String systemValue = System.getenv(key);
        if (systemValue != null) {
            return systemValue;
        }

        // Fall back to .env file
        if (dotenv != null) {
            return dotenv.get(key);
        }

        return null;
    }

    /**
     * Checks if an environment variable is set (in either system env or .env file).
     *
     * @param key the environment variable name
     * @return true if the variable has a non-null value
     */
    public static boolean isSet(String key) {
        return getEnv(key) != null;
    }

    /**
     * Resets the loader state. Package-private for testing.
     */
    static synchronized void reset() {
        dotenv = null;
        initialized = false;
    }
}
