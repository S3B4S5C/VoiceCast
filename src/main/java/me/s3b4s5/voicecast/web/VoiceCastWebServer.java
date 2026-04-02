package me.s3b4s5.voicecast.web;

import com.sun.net.httpserver.HttpServer;
import me.s3b4s5.voicecast.VoiceCast;
import me.s3b4s5.voicecast.web.handlers.ApiHandlers;
import me.s3b4s5.voicecast.web.handlers.StaticHandlers;

import java.io.IOException;
import java.net.InetSocketAddress;

public final class VoiceCastWebServer {

    private final ApiHandlers api;
    private HttpServer server;
    private InetSocketAddress bound;

    public VoiceCastWebServer(ApiHandlers api) {
        this.api = api;
    }

    /**
     * @param host bind host (e.g. 0.0.0.0)
     * @param port port to bind. Use 0 to let OS pick a free port.
     * @return actual bound address (host+port)
     */
    public synchronized InetSocketAddress start(String host, int port) {
        if (server != null) return bound;

        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            bound = server.getAddress();
        } catch (IOException e) {
            VoiceCast.LOGGER.atSevere().log("VoiceCast: failed to bind " + host + ":" + port + " -> " + e);
            throw new RuntimeException("Failed to bind web server on " + host + ":" + port, e);
        }

        server.createContext("/health", api::health);
        server.createContext("/api/info", api::info);
        server.createContext("/api/connect", api::connect);
        server.createContext("/api/disconnect", api::disconnect);
        server.createContext("/api/spells", api::spells);
        server.createContext("/api/cast", api::cast);

        server.createContext("/", StaticHandlers::handleStatic);

        server.start();

        VoiceCast.LOGGER.atInfo().log("web bound on " + bound.getHostString() + ":" + bound.getPort());
        return bound;
    }

    public synchronized InetSocketAddress getBoundAddress() {
        return bound;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            bound = null;
        }
    }
}