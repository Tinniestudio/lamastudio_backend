package com.tinniestudio.worker.storage;

import com.tinniestudio.worker.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioWorkerStorageService implements WorkerStorageService {

    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    @Override
    public void download(String storageKey, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        log.debug("Downloading {} → {}", storageKey, targetPath);
        s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .build(),
            ResponseTransformer.toFile(targetPath)
        );
    }

    @Override
    public void upload(String storageKey, Path sourcePath) throws IOException {
        log.debug("Uploading {} → {}", sourcePath, storageKey);
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .build(),
            RequestBody.fromFile(sourcePath)
        );
    }

    @Override
    public void uploadDirectory(String keyPrefix, Path sourceDir) throws IOException {
        try (var stream = Files.walk(sourceDir)) {
            var files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String relative = sourceDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                upload(key, file);
            }
        }
    }
}
