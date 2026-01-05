package dev.reviewarena.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerContainerRegistryTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        DockerContainerRegistry.clearForTesting();
    }

    @Test
    void register_addsContainerToActiveSet() {
        DockerContainerRegistry.register("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void register_multipleContainers() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");
        DockerContainerRegistry.register("codex");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(3);
    }

    @Test
    void register_sameContainerTwice_noDuplicate() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void register_nullOrBlank_ignored() {
        DockerContainerRegistry.register(null);
        DockerContainerRegistry.register("");
        DockerContainerRegistry.register("   ");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void unregister_removesContainer() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");
        DockerContainerRegistry.unregister("claude");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void unregister_nonExistent_noError() {
        DockerContainerRegistry.unregister("nonexistent");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void unregister_nullOrBlank_noError() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.unregister(null);
        DockerContainerRegistry.unregister("");
        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(1);
    }

    @Test
    void stopAllContainers_clearsRegistry() {
        DockerContainerRegistry.register("claude");
        DockerContainerRegistry.register("gemini");

        // Note: This will attempt to run 'docker stop' which may fail in test env
        // but should still clear the registry
        DockerContainerRegistry.stopAllContainers();

        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(0);
    }

    @Test
    void stopContainer_isIdempotent() {
        // Calling stopContainer on a non-existent container should not throw
        // This is important because close() and shutdown hook may both call it
        DockerContainerRegistry.stopContainer("nonexistent-container");
        // No exception = success
    }

    @Test
    void stopContainer_handlesNullGracefully() {
        // stopContainer should handle null without throwing
        DockerContainerRegistry.stopContainer(null);
        // No exception = success
    }

    @Test
    void concurrentRegistration_threadSafe() throws InterruptedException {
        int threadCount = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                DockerContainerRegistry.register("container-" + index);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(DockerContainerRegistry.getActiveCount()).isEqualTo(threadCount);
    }
}
