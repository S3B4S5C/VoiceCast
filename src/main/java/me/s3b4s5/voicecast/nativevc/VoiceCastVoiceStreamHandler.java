package me.s3b4s5.voicecast.nativevc;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.VoiceData;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.modules.voice.VoicePlayerState;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public final class VoiceCastVoiceStreamHandler extends SimpleChannelInboundHandler<Packet> {

    private final PacketHandler packetHandler;
    private final VoiceCastNativeService service;
    private final VoiceModule voiceModule;
    private final HytaleLogger logger;

    private volatile PlayerRef cachedPlayerRef;
    private volatile boolean loggedFirstPacket = false;

    public VoiceCastVoiceStreamHandler(@Nonnull PacketHandler packetHandler, @Nonnull VoiceCastNativeService service) {
        this.packetHandler = packetHandler;
        this.service = service;
        this.voiceModule = VoiceModule.get();
        this.logger = this.voiceModule.getLogger();
    }

    @Override
    public void handlerAdded(@Nonnull ChannelHandlerContext ctx) throws Exception {
        this.packetHandler.setChannel(StreamType.Voice, ctx.channel());
        if (this.packetHandler instanceof GamePacketHandler gph) {
            this.cachedPlayerRef = gph.getPlayerRef();
        }
        super.handlerAdded(ctx);
    }

    @Override
    protected void channelRead0(@Nonnull ChannelHandlerContext ctx, @Nonnull Packet packet) {
        if (!loggedFirstPacket) {
            loggedFirstPacket = true;
            logger.at(Level.FINE).log("[VoiceCast-VoiceStream] First packet from %s: %s", packetHandler.getIdentifier(), packet.getClass().getSimpleName());
        }

        PlayerRef playerRef = getPlayerRef();
        if (playerRef == null) return;

        if (packet instanceof VoiceData data) {
            handleVoiceData(playerRef, data);
        }
    }

    private void handleVoiceData(@Nonnull PlayerRef playerRef, @Nonnull VoiceData data) {
        if (!voiceModule.isVoiceEnabled()) return;
        if (voiceModule.isShutdown()) return;

        VoicePlayerState state = voiceModule.getPlayerState(playerRef.getUuid());
        if (state == null) return;
        if (state.isRoutingDisabled()) return;
        if (state.isSilenced()) return;
        if (voiceModule.isPlayerMuted(playerRef.getUuid())) return;

        if (!state.checkRateLimit(voiceModule.getMaxPacketsPerSecond(), voiceModule.getBurstCapacity())) return;
        if (data.opusData.length == 0) return;
        if (data.opusData.length > voiceModule.getMaxPacketSize()) return;

        service.onVoiceData(playerRef.getUuid(), playerRef.getUsername(), data);

        voiceModule.getVoiceExecutor(playerRef.getUuid()).execute(() -> {
            try {
                voiceModule.getVoiceRouter().routeVoiceFromCache(playerRef, data);
                state.resetConsecutiveErrors();
            } catch (Exception e) {
                int failures = state.incrementConsecutiveErrors();
                if (failures >= 10) {
                    state.setRoutingDisabled(true);
                    logger.at(Level.WARNING).log("[VoiceCast-VoiceStream] Disabled routing for %s after %d errors", playerRef.getUuid(), failures);
                } else {
                    logger.at(Level.SEVERE).withCause(e).log("[VoiceCast-VoiceStream] routeVoiceFromCache failed for %s (%d/%d)", playerRef.getUuid(), failures, 10);
                }
            }
        });
    }

    private PlayerRef getPlayerRef() {
        if (cachedPlayerRef != null) return cachedPlayerRef;
        if (packetHandler instanceof GamePacketHandler gph) {
            cachedPlayerRef = gph.getPlayerRef();
        }
        return cachedPlayerRef;
    }

    @Override
    public void channelInactive(@Nonnull ChannelHandlerContext ctx) throws Exception {
        packetHandler.compareAndSetChannel(StreamType.Voice, ctx.channel(), null);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(@Nonnull ChannelHandlerContext ctx, @Nonnull Throwable cause) {
        logger.at(Level.WARNING).withCause(cause).log("[VoiceCast-VoiceStream] Exception for %s", packetHandler.getIdentifier());
        ctx.close();
    }
}