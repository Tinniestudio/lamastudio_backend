package com.tinniestudio.api.modules.upload.dto;

import com.tinniestudio.api.shared.storage.CompletedPartInfo;

import java.util.List;

/**
 * Optional body for POST /uploads/sessions/{id}/complete. Only meaningful for
 * {@code UploadType.SUBTITLE} — ignored for every other upload type, since RAW_VIDEO/TRAILER
 * need no extra metadata to finish (the worker fills in duration/codec/etc. during processing)
 * and THUMBNAIL is just a stored file with no additional fields.
 */
public record CompleteUploadRequest(
    String languageCode,
    String label,
    Boolean isDefault,
    // Only meaningful for a multipart session (RAW_VIDEO/TRAILER) — the client's own tracked
    // {partNumber, eTag} list (each eTag returned by S3 in that part's PUT response header).
    // Null/absent for every non-multipart upload type.
    List<CompletedPartInfo> parts
) {}
