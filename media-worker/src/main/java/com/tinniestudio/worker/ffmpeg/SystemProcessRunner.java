package com.tinniestudio.worker.ffmpeg;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class SystemProcessRunner implements ProcessRunner {

    @Override
    public String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit=" + exitCode + "): " + String.join(" ", command)
                + "\nOutput: " + output);
        }
        return output;
    }
}
