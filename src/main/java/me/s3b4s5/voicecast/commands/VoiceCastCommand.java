package me.s3b4s5.voicecast.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.s3b4s5.voicecast.VoiceCast;
import me.s3b4s5.voicecast.web.services.SessionService;
import me.s3b4s5.voicecast.web.share.VoiceCastShare;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;

public class VoiceCastCommand extends CommandBase {

    private final VoiceCast plugin;

    public VoiceCastCommand(VoiceCast plugin) {
        super("voicecast", "Generate a VoiceCast web link to bind your microphone session.");
        this.plugin = plugin;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void executeSync(@NonNullDecl CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) return;

        SessionService sessions = plugin.getSessions();
        InetSocketAddress bound = (plugin.getWebServer() != null) ? plugin.getWebServer().getBoundAddress() : null;

        if (sessions == null || bound == null) {
            player.sendMessage(
                    Message.raw("VoiceCast is not ready (web server disabled or not started).")
                            .color("#ff4d5a")
            );
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            player.sendMessage(
                    Message.raw("VoiceCast: player world is not available.")
                            .color("#ff4d5a")
            );
            return;
        }

        world.execute(() -> {
            UUID uuid = resolvePlayerUuidOnWorldThread(player, world);
            if (uuid == null) {
                player.sendMessage(
                        Message.raw("Could not resolve your UUID.")
                                .color("#ff4d5a")
                );
                return;
            }

            String code = sessions.createCodeForPlayer(uuid.toString());

            int port = bound.getPort();
            var candidates = VoiceCastShare.buildCandidates(port, plugin.getPublicBaseUrl());
            String share = VoiceCastShare.encodeShare(code, candidates);

            String baseUrl = plugin.getPublicBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://" + bound.getHostString() + ":" + port; // fallback
            }

            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String uiUrl = baseUrl + "/?share=" + share;

            player.sendMessage(
                    Message.empty()
                            .insert(Message.raw("VoiceCast").color("#2ea8ff").bold(true))
                            .insert(Message.raw(" — microphone link").color("#aab3c2"))
            );

            player.sendMessage(
                    Message.raw("Open VoiceCast")
                            .color("#2ea8ff")
                            .bold(true)
                            .link(uiUrl)
            );

            player.sendMessage(
                    Message.raw("Keep this link private. It contains connection info.")
                            .color("#aab3c2")
            );
        });
    }

    private static UUID resolvePlayerUuidOnWorldThread(Player player, World world) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return null;

        Store<EntityStore> store = world.getEntityStore().getStore();
        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
        return (uuidComp != null) ? uuidComp.getUuid() : null;
    }

    @NonNullDecl
    @Override
    public Set<String> getAliases() {
        return Set.of("vc");
    }
}