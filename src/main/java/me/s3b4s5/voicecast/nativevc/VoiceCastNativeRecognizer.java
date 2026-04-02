package me.s3b4s5.voicecast.nativevc;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface VoiceCastNativeRecognizer {

    CompletableFuture<Result> recognize(UUID playerUuid, byte[] opusBytes, String language);

    record Result(String spellId, float confidence, String rawText) {
    }
}