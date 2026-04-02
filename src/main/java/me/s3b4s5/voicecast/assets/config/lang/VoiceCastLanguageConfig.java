package me.s3b4s5.voicecast.assets.config.lang;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import me.s3b4s5.voicecast.assets.codec.lang.VoiceCastLanguageCodec;

import javax.annotation.Nonnull;

public final class VoiceCastLanguageConfig extends VoiceCastLanguage {

    @Nonnull
    public static final String ASSET_TYPE_ID = "Language";

    @Nonnull
    public static final BuilderCodec<VoiceCastLanguageConfig> ABSTRACT_CODEC;

    static {
        var b = BuilderCodec.builder(VoiceCastLanguageConfig.class, VoiceCastLanguageConfig::new);
        VoiceCastLanguageCodec.appendFields(b);

        ABSTRACT_CODEC = b.afterDecode((o, _) -> {
            if (o.id == null) o.id = "";
            if (o.languageCode == null) o.languageCode = "en_en";
            if (o.values == null) o.values = new String[0];
        }).build();
    }
}