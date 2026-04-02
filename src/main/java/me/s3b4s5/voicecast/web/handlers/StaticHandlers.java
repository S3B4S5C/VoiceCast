package me.s3b4s5.voicecast.web.handlers;

import com.sun.net.httpserver.HttpExchange;
import me.s3b4s5.voicecast.web.http.HttpUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class StaticHandlers {

    public static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        switch (path) {
            case "/", "/index.html" -> {
                resource(ex, "web/index.html", "text/html; charset=utf-8");
                return;
            }
            case "/app.js" -> {
                resource(ex, "web/app.js", "application/javascript; charset=utf-8");
                return;
            }
            case "/styles.css" -> {
                resource(ex, "web/styles.css", "text/css; charset=utf-8");
                return;
            }
        }

        text(ex, "Not Found");
    }

    private static void resource(HttpExchange ex, String resourcePath, String contentType) throws IOException {
        InputStream in = StaticHandlers.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            text(ex, "Missing resource: " + resourcePath);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType);
        byte[] data = in.readAllBytes();
        HttpUtil.bytes(ex, 200, data);
    }

    private static void text(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        HttpUtil.bytes(ex, 404, body.getBytes(StandardCharsets.UTF_8));
    }

    private StaticHandlers() {}
}