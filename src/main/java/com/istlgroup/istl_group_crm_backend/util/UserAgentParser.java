package com.istlgroup.istl_group_crm_backend.util;

/**
 * UserAgentParser — lightweight server-side User-Agent parsing.
 * No external dependency; covers the browsers and platforms the CRM sees in
 * practice. Client-sent values are treated as hints only — the server-parsed
 * values from this class are what get stored.
 */
public final class UserAgentParser {

    private UserAgentParser() { }

    public static class ParsedUa {
        public String browser         = "Unknown";
        public String browserVersion  = "";
        public String operatingSystem = "Unknown";
        public String deviceType      = "DESKTOP";   // MOBILE | LAPTOP | DESKTOP | TABLET
        public String deviceName      = "";
    }

    public static ParsedUa parse(String ua) {
        ParsedUa p = new ParsedUa();
        if (ua == null || ua.isBlank()) return p;
        String u = ua;

        // ── Operating system ──────────────────────────────────────────────
        if (u.contains("Windows NT 10.0")) {
            // Windows 11 also reports NT 10.0 — cannot distinguish server-side
            p.operatingSystem = "Windows 10/11";
        } else if (u.contains("Windows NT 6.3")) p.operatingSystem = "Windows 8.1";
        else if (u.contains("Windows NT 6.1"))   p.operatingSystem = "Windows 7";
        else if (u.contains("Windows"))          p.operatingSystem = "Windows";
        else if (u.contains("Android"))          p.operatingSystem = "Android" + versionAfter(u, "Android ");
        else if (u.contains("iPhone OS") || u.contains("iPad; CPU OS")) p.operatingSystem = "iOS";
        else if (u.contains("Mac OS X"))         p.operatingSystem = "macOS";
        else if (u.contains("CrOS"))             p.operatingSystem = "ChromeOS";
        else if (u.contains("Linux"))            p.operatingSystem = "Linux";

        // ── Browser (order matters: Edge/Opera embed "Chrome") ────────────
        if (u.contains("Edg/")) {
            p.browser = "Edge";                p.browserVersion = tokenAfter(u, "Edg/");
        } else if (u.contains("OPR/")) {
            p.browser = "Opera";               p.browserVersion = tokenAfter(u, "OPR/");
        } else if (u.contains("SamsungBrowser/")) {
            p.browser = "Samsung Internet";    p.browserVersion = tokenAfter(u, "SamsungBrowser/");
        } else if (u.contains("Firefox/")) {
            p.browser = "Firefox";             p.browserVersion = tokenAfter(u, "Firefox/");
        } else if (u.contains("Chrome/")) {
            p.browser = "Chrome";              p.browserVersion = tokenAfter(u, "Chrome/");
        } else if (u.contains("Safari/") && u.contains("Version/")) {
            p.browser = "Safari";              p.browserVersion = tokenAfter(u, "Version/");
        } else if (u.contains("MSIE") || u.contains("Trident/")) {
            p.browser = "Internet Explorer";
        }

        // ── Device type ───────────────────────────────────────────────────
        if (u.contains("iPad") || (u.contains("Android") && !u.contains("Mobile"))) {
            p.deviceType = "TABLET";
        } else if (u.contains("Mobile") || u.contains("iPhone")) {
            p.deviceType = "MOBILE";
        } else {
            // Laptops and desktops share identical user agents; a battery-
            // capable client hint from the frontend upgrades this to LAPTOP.
            p.deviceType = "DESKTOP";
        }

        // ── Friendly device name ──────────────────────────────────────────
        if (u.contains("iPhone"))      p.deviceName = "iPhone";
        else if (u.contains("iPad"))   p.deviceName = "iPad";
        else if (u.contains("Android")) {
            int start = u.indexOf('(');
            int end   = u.indexOf(')', start);
            if (start >= 0 && end > start) {
                String[] parts = u.substring(start + 1, end).split(";");
                if (parts.length > 0) {
                    String candidate = parts[parts.length - 1].trim();
                    if (candidate.contains("Build/")) {
                        candidate = candidate.substring(0, candidate.indexOf("Build/")).trim();
                    }
                    if (!candidate.isBlank() && candidate.length() <= 60) p.deviceName = candidate;
                }
            }
        } else if (u.contains("Macintosh")) p.deviceName = "Mac";
        else if (u.contains("Windows"))     p.deviceName = "Windows PC";

        return p;
    }

    private static String tokenAfter(String ua, String marker) {
        int i = ua.indexOf(marker);
        if (i < 0) return "";
        int start = i + marker.length();
        int end = start;
        while (end < ua.length() && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '.')) end++;
        return ua.substring(start, end);
    }

    private static String versionAfter(String ua, String marker) {
        String v = tokenAfter(ua, marker);
        return v.isBlank() ? "" : " " + v;
    }
}
