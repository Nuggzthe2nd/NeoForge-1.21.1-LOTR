package net.nuggz.lotrmc.network;

import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Sent from client → server when a player confirms a name in BrandingScreen.
 *
 * On receipt:
 *   1. Finds the orc by UUID
 *   2. Validates it still has a pit and the pit has no leader
 *   3. Validates the name (non-empty, max 32 chars)
 *   4. Consumes one Brand item from the player's inventory
 *   5. Calls orc.applyBranding() and pit.setLeader()
 *   6. Sends confirmation message to player
 */
public record BrandingPacket(UUID orcUUID, String name) implements CustomPacketPayload {

    public static final int MAX_NAME_LENGTH = 32;

    public static final CustomPacketPayload.Type<BrandingPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("lotrmc", "branding"));

    public static final StreamCodec<FriendlyByteBuf, BrandingPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> packet.encode(buf),
                    BrandingPacket::decode
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // -------------------------------------------------------------------------
    // Encoding / decoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(orcUUID);
        buf.writeUtf(name, MAX_NAME_LENGTH);
    }

    public static BrandingPacket decode(FriendlyByteBuf buf) {
        return new BrandingPacket(buf.readUUID(), buf.readUtf(MAX_NAME_LENGTH));
    }

    // -------------------------------------------------------------------------
    // Server-side handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ServerLevel level = player.serverLevel();

            // --- Find the orc ---
            Entity entity = level.getEntity(orcUUID);
            if (!(entity instanceof OrcEntity orc)) {
                player.sendSystemMessage(Component.literal(
                        "§cThe orc could not be found."));
                return;
            }

            // --- Validate pit ---
            BlockPos pitPos = orc.getPitPos();
            if (pitPos == null) {
                player.sendSystemMessage(Component.literal(
                        "§cThis orc has no pit affiliation."));
                return;
            }

            if (!(level.getBlockEntity(pitPos) instanceof MudpitBlockEntity pit)) {
                player.sendSystemMessage(Component.literal(
                        "§cThis orc's pit no longer exists."));
                return;
            }

            if (pit.hasLeader()) {
                player.sendSystemMessage(Component.literal(
                        "§cThis pit already has a leader."));
                return;
            }

            // --- Validate name ---
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "§cName cannot be empty."));
                return;
            }

            // --- Consume Brand item ---
            boolean consumed = consumeBrandItem(player);
            if (!consumed) {
                player.sendSystemMessage(Component.literal(
                        "§cYou need a Brand to mark a leader."));
                return;
            }

            // --- Apply branding ---
            orc.applyBranding(trimmed);
            pit.setLeader(orc.getUUID());

            // --- Confirm to player ---
            player.sendSystemMessage(Component.literal(
                    "§8" + trimmed + " §7has been branded as pit leader."));
            player.sendSystemMessage(Component.literal(
                    orc.getLeaderData().toDisplayString()));
        });
    }

    private boolean consumeBrandItem(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && stack.getItem() instanceof net.nuggz.lotrmc.item.BrandItem) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
