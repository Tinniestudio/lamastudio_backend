package com.tinniestudio.worker.storage;

import java.io.IOException;
import java.nio.file.Path;

public interface WorkerStorageService {
    /** Download object at storageKey to targetPath on local disk. */
    void download(String storageKey, Path targetPath) throws IOException;

    /** Upload a single local file to storageKey in the configured bucket. */
    void upload(String storageKey, Path sourcePath) throws IOException;

    /**
     * Upload all regular files under sourceDir recursively.
     * Each file's storage key = keyPrefix + "/" + relative path from sourceDir.
     */
    void uploadDirectory(String keyPrefix, Path sourceDir) throws IOException;
}
