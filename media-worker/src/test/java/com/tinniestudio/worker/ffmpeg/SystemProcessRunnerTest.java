package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemProcessRunnerTest {

    private final SystemProcessRunner runner = new SystemProcessRunner();

    @Test
    void returnsOutputWhenCommandCompletesWithinTimeout() throws Exception {
        String output = runner.run(List.of("sh", "-c", "echo hello-world"), 30);

        assertThat(output).contains("hello-world");
    }

    @Test
    void throwsRuntimeExceptionWhenExitCodeNonZero() {
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "exit 3"), 30))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(ProcessTimeoutException.class)
            .hasMessageContaining("exit=3");
    }

    @Test
    void killsHungProcessAndThrowsProcessTimeoutExceptionInsteadOfBlockingForever() {
        // 1-second timeout against a command that would otherwise run for 30s.
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "sleep 30"), 1))
            .isInstanceOf(ProcessTimeoutException.class)
            .hasMessageContaining("timed out");

        long elapsedMs = System.currentTimeMillis() - start;
        // Must return shortly after the configured timeout, proving it did not
        // block for the full 30s sleep (the pre-fix no-arg waitFor() behavior).
        assertThat(elapsedMs).isLessThan(15_000);
    }
}
