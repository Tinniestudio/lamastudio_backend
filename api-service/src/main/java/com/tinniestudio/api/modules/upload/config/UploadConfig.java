package com.tinniestudio.api.modules.upload.config;

import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Component
public class UploadConfig {

    // Indie/prosumer tier — strict MIME check at API layer.
    // RAW_VIDEO covers: MP4, MOV, MKV (universal), MPEG-TS/M2TS (AVCHD cameras), AVI (legacy).
    private static final Map<UploadType, Set<String>> ALLOWED_MIME_TYPES = Map.of(
        UploadType.RAW_VIDEO, Set.of(
            "video/mp4",          // MP4 — GoPro, DJI, Sony XAVC-S, Canon, Panasonic, phones
            "video/quicktime",    // MOV — Apple, Canon mirrorless, Sony
            "video/x-matroska",   // MKV — Android, OBS, Panasonic
            "video/mp2t",         // MPEG-TS (.mts) — AVCHD Sony/Panasonic/Canon camcorders
            "video/x-m2ts",       // M2TS (.m2ts) — Sony XAVC AVCHD variant
            "video/x-msvideo",    // AVI — legacy prosumer DSLR/camcorders
            "video/avi"           // AVI MIME variant
        ),
        UploadType.TRAILER,   Set.of("video/mp4", "video/quicktime"),
        UploadType.THUMBNAIL, Set.of("image/jpeg", "image/png", "image/webp"),
        UploadType.SUBTITLE,  Set.of("text/vtt", "application/x-subrip")
    );

    private static final Map<UploadType, Long> MAX_BYTES = Map.of(
        UploadType.RAW_VIDEO, 10L * 1024 * 1024 * 1024,  // 10 GB
        UploadType.TRAILER,    2L * 1024 * 1024 * 1024,   // 2 GB
        UploadType.THUMBNAIL,        10L * 1024 * 1024,   // 10 MB
        UploadType.SUBTITLE,          5L * 1024 * 1024    // 5 MB
    );

    public static final Duration PRESIGNED_URL_TTL = Duration.ofMinutes(30);
    public static final String VIDEO_PROCESS_QUEUE = "media.video.process";

    public boolean isMimeTypeAllowed(UploadType type, String mimeType) {
        Set<String> allowed = ALLOWED_MIME_TYPES.get(type);
        return allowed != null && allowed.contains(mimeType);
    }

    public long getMaxBytes(UploadType type) {
        return MAX_BYTES.getOrDefault(type, 0L);
    }
}
