package net.nuggz.lotrmc.item;

import net.nuggz.lotrmc.client.screen.BrandingScreen;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Brand item — used on an orc to mark it as a pit leader.
 *
 * Right-click on any OrcEntity:
 *   Server side: validates the orc has a pit and the pit has no leader,
 *                sends feedback to the player if invalid.
 *   Client side: opens BrandingScreen for the player to enter a name.
 *
 * The item is consumed on the server in BrandingPacket.handle() only if
 * all validation passes and the player confirms a name. Cancelling the
 * screen costs nothing.
 *
 * Registration: add to ModItems.ITEMS as "brand".
 */
public class BrandItem extends Item {

    public BrandItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    /**
     * Called when the player right-clicks any entity.
     * We override interactLivingEntity on the entity side for cleaner dispatch,
     * but also handle it here for the client-side screen open.
     */
    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player,
            net.minecraft.world.entity.LivingEntity target,
            InteractionHand hand) {

        if (!(target instanceof OrcEntity orc)) {
            return InteractionResult.PASS;
        }

        if (player.level().isClientSide()) {
            // Client: run validation feedback, then open screen if ok
            openBrandingScreenIfValid(orc, player);
            return InteractionResult.SUCCESS;
        }

        // Server: validation is handled fully in BrandingPacket when confirmed.
        // We do a quick pre-check here to give immediate feedback without
        // waiting for the player to type a name.
        return validateServerSide(orc, player);
    }

    // -------------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    private void openBrandingScreenIfValid(OrcEntity orc, Player player) {
        // pitPos and isLeader are server-only fields (not synced via EntityDataAccessor),
        // so we can't pre-validate here. The server does full validation in
        // BrandingPacket.handle() and sends the appropriate feedback message.
        Minecraft.getInstance().setScreen(new BrandingScreen(orc.getUUID()));
    }

    private InteractionResult validateServerSide(OrcEntity orc, Player player) {
        if (orc.getPitPos() == null) {
            player.sendSystemMessage(Component.literal(
                    "§cThis orc has no pit affiliation — "
                            + "only orcs spawned from a mudpit can be branded."));
            return InteractionResult.FAIL;
        }

        if (orc.isLeader()) {
            player.sendSystemMessage(Component.literal(
                    "§cThis orc is already a pit leader."));
            return InteractionResult.FAIL;
        }

        var level = (net.minecraft.server.level.ServerLevel) orc.level();
        if (level.getBlockEntity(orc.getPitPos()) instanceof MudpitBlockEntity pit
                && pit.hasLivingLeader(level)) {
            player.sendSystemMessage(Component.literal(
                    "§cThis pit already has a leader. "
                            + "The current leader must die before a new one can be branded."));
            return InteractionResult.FAIL;
        }

        // Validation passed — client will open the screen and send the packet
        return InteractionResult.SUCCESS;
    }
}