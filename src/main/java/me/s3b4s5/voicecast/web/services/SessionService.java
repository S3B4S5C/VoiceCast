package me.s3b4s5.voicecast.web.services;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionService {

    public record PendingCode(String playerUuid, Instant expiresAt) {}
    public record Session(String playerUuid, Instant expiresAt, RateWindow rate) {}
    public record ConnectResult(boolean ok, String token, String playerUuid, long expiresInSeconds, String error) {}

    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final SecureRandom rng = new SecureRandom();

    private final long codeTtlSeconds;
    private final long sessionTtlSeconds;

    public SessionService(long codeTtlSeconds, long sessionTtlSeconds) {
        this.codeTtlSeconds = codeTtlSeconds;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public String createCodeForPlayer(String playerUuid) {
        String code = newCode();
        codes.put(code, new PendingCode(playerUuid, Instant.now().plusSeconds(codeTtlSeconds)));
        return code;
    }

    public ConnectResult connectWithCode(String code) {
        PendingCode pending = codes.remove(code);
        if (pending == null) {
            return new ConnectResult(false, null, null, 0, "invalid_code");
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            return new ConnectResult(false, null, null, 0, "expired_code");
        }

        String token = UUID.randomUUID().toString();
        Instant exp = Instant.now().plusSeconds(sessionTtlSeconds);
        sessions.put(token, new Session(pending.playerUuid(), exp, new RateWindow()));

        return new ConnectResult(true, token, pending.playerUuid(), sessionTtlSeconds, null);
    }

    public void disconnect(String token) {
        sessions.remove(token);
    }

    public Session requireSession(String token) {
        Session s = sessions.get(token);
        if (s == null) return null;
        if (Instant.now().isAfter(s.expiresAt())) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public boolean tryConsumeCast(Session s, int limit, long windowMs) {
        return s.rate.tryConsume(limit, windowMs);
    }

    private String newCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(7);
        for (int i = 0; i < 7; i++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        return sb.toString();
    }

    public static final class RateWindow {
        private long windowStartMs = System.currentTimeMillis();
        private int used = 0;

        public synchronized boolean tryConsume(int limit, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStartMs > windowMs) {
                windowStartMs = now;
                used = 0;
            }
            if (used >= limit) return false;
            used++;
            return true;
        }
    }
}