package net.nuggz.lotrmc.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.nuggz.lotrmc.client.screen.RaidResultScreen;
import net.nuggz.lotrmc.warmap.RaidParty;
import net.nuggz.lotrmc.warmap.RaidSimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: delivers raid result to show the result screen.
 *
 * Sent when a raid party's completionTime is reached and they return home.
 * Opens RaidResultScreen on the client with a narrative description and loot.
 */
public record RaidResultPacket(
        String leaderName,
        String targetLabel,
        int survivors,
        int casualties,
        int scarsGained,
        List<ItemStack> loot,
        String narrative
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaidResultPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("lotrmc", "raid_result"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, RaidResultPacket> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, pkt) -> pkt.encode(buf),
                    RaidResultPacket::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Server-side builder
    // -------------------------------------------------------------------------

    public static void send(ServerPlayer player, RaidParty party,
                            RaidSimulator.RaidResult result) {
        int totalScars = result.scarsPerOrc.values().stream()
                .mapToInt(Integer::intValue).sum();

        PacketDistributor.sendToPlayer(player, new RaidResultPacket(
                party.leaderName,
                party.targetLabel,
                result.survivorUUIDs.size(),
                result.casualtyUUIDs.size(),
                totalScars,
                result.loot,
                result.narrative
        ));
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(leaderName != null ? leaderName : "");
        buf.writeUtf(targetLabel != null ? targetLabel : "");
        buf.writeInt(survivors);
        buf.writeInt(casualties);
        buf.writeInt(scarsGained);
        buf.writeInt(loot.size());
        for (ItemStack stack : loot) {
            buf.writeResourceLocation(
                    stack.getItem().builtInRegistryHolder().key().location());
            buf.writeInt(stack.getCount());
        }
        buf.writeUtf(narrative != null ? narrative : "");
    }

    public static RaidResultPacket decode(FriendlyByteBuf buf) {
        String leader    = buf.readUtf();
        String target    = buf.readUtf();
        int survivors    = buf.readInt();
        int casualties   = buf.readInt();
        int scars        = buf.readInt();
        int lootCount    = buf.readInt();
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < lootCount; i++) {
            var key = buf.readResourceLocation();
            int count = buf.readInt();
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
            loot.add(new ItemStack(item, count));
        }
        String narrative = buf.readUtf();
        return new RaidResultPacket(leader, target, survivors, casualties,
                scars, loot, narrative);
    }

    // -------------------------------------------------------------------------
    // Client handling
    // -------------------------------------------------------------------------

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> showResultScreen(this));
    }

    @OnlyIn(Dist.CLIENT)
    private static void showResultScreen(RaidResultPacket pkt) {
        Minecraft.getInstance().setScreen(new RaidResultScreen(pkt));
    }
}
