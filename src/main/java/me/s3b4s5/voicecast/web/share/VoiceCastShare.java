package me.s3b4s5.voicecast.web.share;

import javax.annotation.Nullable;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

public final class VoiceCastShare {

    public record Candidate(String baseUrl, int priority) {}

    private VoiceCastShare() {}

    public static List<Candidate> buildCandidates(int port, @Nullable String publicBaseUrl) {
        ArrayList<Candidate> out = new ArrayList<>();

        String cfg = normalizeBase(publicBaseUrl, port);
        if (cfg != null) {
            out.add(new Candidate(cfg, 2000)); // highest priority
        }

        for (InetAddress ip : getLocalIPv4s()) {
            String host = ip.getHostAddress();
            int prio = 800;

            if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) prio = 1000;
            if (host.startsWith("26.")) prio = 950;
            if (host.startsWith("100.")) prio = 930;

            out.add(new Candidate("http://" + host + ":" + port, prio));
        }

        out.add(new Candidate("http://127.0.0.1:" + port, 10));
        out.add(new Candidate("http://localhost:" + port, 5));

        Map<String, Integer> best = new HashMap<>();
        for (Candidate c : out) {
            String key = stripTrailingSlash(c.baseUrl());
            best.merge(key, c.priority(), Math::max);
        }

        ArrayList<Candidate> dedup = new ArrayList<>();
        for (var e : best.entrySet()) dedup.add(new Candidate(e.getKey(), e.getValue()));
        dedup.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return dedup;
    }

    private static @Nullable String normalizeBase(@Nullable String base, int port) {
        if (base == null || base.isBlank()) return null;

        String b = base.trim();
        b = stripTrailingSlash(b);

        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            if (b.matches("^[^/]+:\\d+$")) return "http://" + b;
            return "http://" + b + ":" + port;
        }

        return b;
    }

    private static String stripTrailingSlash(String s) {
        return s == null ? null : s.replaceAll("/+$", "");
    }

    private static List<InetAddress> getLocalIPv4s() {
        ArrayList<InetAddress> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();

            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                } catch (Throwable ignored) {}

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    ips.add(a);
                }
            }
        } catch (Throwable ignored) {}
        return ips;
    }

    public static String encodeShare(String code, List<Candidate> candidates) {
        String json = toJson(code, candidates);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJson(String code, List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":\"").append(escape(code)).append("\",\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"baseUrl\":\"").append(escape(c.baseUrl())).append("\",\"priority\":").append(c.priority()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}