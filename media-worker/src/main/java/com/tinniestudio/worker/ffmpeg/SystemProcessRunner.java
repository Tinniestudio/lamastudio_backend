package com.tinniestudio.worker.ffmpeg;

import com.tinniestudio.worker.config.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SystemProcessRunner implements ProcessRunner {

    private final long timeoutSeconds;

    public SystemProcessRunner(WorkerProperties workerProperties) {
        this.timeoutSeconds = workerProperties.getFfmpeg().getTimeoutSeconds();
    }

    @Override
    public String run(List<String> command) throws IOException, InterruptedException {
        // Redirect stdout/stderr to a temp file instead of reading via a pipe
        // (InputStream#readAllBytes() blocks until the pipe closes, which would
        // hang forever alongside a stuck process even with a waitFor timeout).
        Path outputFile = Files.createTempFile("worker-proc-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String message = "Command timed out after " + timeoutSeconds + "s and was killed: "
                    + String.join(" ", command);
                log.error(message);
                throw new ProcessTimeoutException(message);
            }

            String output = Files.readString(outputFile);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed (exit=" + exitCode + "): " + String.join(" ", command)
                    + "\nOutput: " + output);
            }
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
