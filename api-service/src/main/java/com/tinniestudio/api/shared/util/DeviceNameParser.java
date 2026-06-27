package com.tinniestudio.api.shared.util;

import org.springframework.util.StringUtils;

public final class DeviceNameParser {

    private DeviceNameParser() {}

    public static String parse(String userAgent) {
        if (!StringUtils.hasText(userAgent)) return "Unknown Device";

        String ua = userAgent.toLowerCase();
        String browser = detectBrowser(ua);
        String os = detectOs(ua);

        if (browser != null && os != null) return browser + " on " + os;
        if (browser != null) return browser;
        if (os != null) return "Browser on " + os;
        return "Unknown Device";
    }

    private static String detectBrowser(String ua) {
        if (ua.contains("edg/") || ua.contains("edge/")) return "Edge";
        if (ua.contains("chrome/") && !ua.contains("chromium")) return "Chrome";
        if (ua.contains("firefox/")) return "Firefox";
        if (ua.contains("safari/") && !ua.contains("chrome")) return "Safari";
        if (ua.contains("opera") || ua.contains("opr/")) return "Opera";
        return null;
    }

    private static String detectOs(String ua) {
        if (ua.contains("iphone") || ua.contains("ipad")) return "iOS";
        if (ua.contains("android")) return "Android";
        if (ua.contains("windows nt")) return "Windows";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        return null;
    }
}
