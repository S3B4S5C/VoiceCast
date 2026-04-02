package me.s3b4s5.voicecast.assets.config.cast;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import me.s3b4s5.voicecast.assets.codec.cast.VoiceCastCastableCodec;

import javax.annotation.Nonnull;

public final class VoiceCastCastableConfig extends VoiceCastCastable {

    @Nonnull
    public static final String ASSET_TYPE_ID = "Castable";

    @Nonnull
    public static final BuilderCodec<VoiceCastCastableConfig> ABSTRACT_CODEC;

    static {
        var b = BuilderCodec.builder(VoiceCastCastableConfig.class, VoiceCastCastableConfig::new);
        VoiceCastCastableCodec.appendFields(b);

        ABSTRACT_CODEC = b.afterDecode((o, extra) -> {
            if (o.id == null) o.id = "";
            if (o.rootInteractionId == null) o.rootInteractionId = "";
            if (o.languageIds == null) o.languageIds = new String[0];
        }).build();
    }
}