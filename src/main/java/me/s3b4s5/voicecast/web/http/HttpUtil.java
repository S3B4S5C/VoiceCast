package me.s3b4s5.voicecast.web.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class HttpUtil {

    public static boolean isNotPost(HttpExchange ex) {
        return !"POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    public static void methodNotAllowed(HttpExchange ex) throws IOException {
        text(ex, 405, "Method Not Allowed");
    }

    public static String getBearerToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null) return null;
        if (!auth.startsWith("Bearer ")) return null;
        return auth.substring("Bearer ".length()).trim();
    }

    public static Map<String, Object> readJsonObject(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        return parseFlatJsonObject(body);
    }

    public static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static double getDouble(Map<String, Object> obj, String key, double def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignored) {}
        }
        return def;
    }

    public static void json(HttpExchange ex, int code, Object body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String s = toJson(body);
        bytes(ex, code, s.getBytes(StandardCharsets.UTF_8));
    }

    public static void text(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        bytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    public static void bytes(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> parseFlatJsonObject(String json) {
        Map<String, Object> out = new HashMap<>();
        if (json == null) return out;

        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);

        String[] parts = s.split(",");
        for (String p : parts) {
            String part = p.trim();
            if (part.isEmpty()) continue;
            int idx = part.indexOf(':');
            if (idx <= 0) continue;

            String k = stripQuotes(part.substring(0, idx).trim());
            String vRaw = part.substring(idx + 1).trim();

            Object v;
            if (vRaw.startsWith("\"") && vRaw.endsWith("\"")) {
                v = stripQuotes(vRaw);
            } else {
                if ("true".equalsIgnoreCase(vRaw)) v = Boolean.TRUE;
                else if ("false".equalsIgnoreCase(vRaw)) v = Boolean.FALSE;
                else if ("null".equalsIgnoreCase(vRaw)) v = null;
                else {
                    try {
                        if (vRaw.contains(".")) v = Double.parseDouble(vRaw);
                        else v = Long.parseLong(vRaw);
                    } catch (Exception e) {
                        v = vRaw;
                    }
                }
            }
            out.put(k, v);
        }

        return out;
    }

    private static String stripQuotes(String s) {
        String t = s;
        if (t.startsWith("\"")) t = t.substring(1);
        if (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + escape(s) + "\"";
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);

        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(String.valueOf(e.getKey()))).append(":").append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }

        if (o instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(x));
            }
            sb.append("]");
            return sb.toString();
        }

        return toJson(String.valueOf(o));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private HttpUtil() {}
}