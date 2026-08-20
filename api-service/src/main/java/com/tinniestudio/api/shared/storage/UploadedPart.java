package com.tinniestudio.api.shared.storage;

public record UploadedPart(int partNumber, String eTag, long sizeBytes) {}
