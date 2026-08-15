package com.tinniestudio.api.modules.upload.dto;

/**
 * Optional body for POST /uploads/sessions/{id}/complete. Only meaningful for
 * {@code UploadType.SUBTITLE} — ignored for every other upload type, since RAW_VIDEO/TRAILER
 * need no extra metadata to finish (the worker fills in duration/codec/etc. during processing)
 * and THUMBNAIL is just a stored file with no additional fields.
 */
public record CompleteUploadRequest(
    String languageCode,
    String label,
    Boolean isDefault
) {}
