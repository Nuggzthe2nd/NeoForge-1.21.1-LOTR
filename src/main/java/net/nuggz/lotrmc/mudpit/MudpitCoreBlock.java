package net.nuggz.lotrmc.mudpit;

import com.mojang.serialization.MapCodec;
import net.nuggz.lotrmc.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * The mudpit core block — sits on the floor of a carved mudpit.
 *
 * Properties:
 *   - High blast resistance so it survives most explosions
 *   - Can be broken by players/enemies (intentional — destroying it ends the pit)
 *   - Visually distinct from balt (darker, slightly glowing texture via model)
 *   - No player interaction (no right-click menu) — items are thrown in, not inserted
 *
 * When destroyed:
 *   - The block entity is lost, spawned orcs from this pit lose affiliation
 *   - No collapse mechanic — the pit just becomes inert balt floor
 */
public class MudpitCoreBlock extends BaseEntityBlock {

    public static final MapCodec<MudpitCoreBlock> CODEC = simpleCodec(p -> new MudpitCoreBlock());

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public MudpitCoreBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(5.0f, 1200.0f) // hard to mine, high blast resistance
                .lightLevel(state -> 2)   // subtle glow — enough to be visible in dark pit
                .noOcclusion()
        );
    }

    // -------------------------------------------------------------------------
    // Block entity
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MudpitBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level, BlockState state,
            BlockEntityType<T> type) {
        // Only tick on server
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.MUDPIT.get(),
                (lvl, pos, st, be) ->
                        MudpitBlockEntity.serverTick((ServerLevel) lvl, pos, st, be));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MudpitBlockEntity pit))
            return InteractionResult.PASS;

        player.sendSystemMessage(Component.literal(
                "§6Biomass: §f" + pit.getBiomass()
                + "  §6Capacity: §f" + pit.getCapacity()));

        if (pit.isGestating()) {
            int ticksLeft = pit.getGestationTicksRemaining();
            int minutes   = ticksLeft / 1200;
            int seconds   = (ticksLeft % 1200) / 20;
            String timeStr = minutes > 0
                    ? minutes + "m " + seconds + "s"
                    : seconds + "s";
            player.sendSystemMessage(Component.literal(
                    "§6Gestating: §f" + pit.getPendingUnits() + " orc(s)"
                    + "  §6Progress: §f" + pit.getGestationPercent() + "% §7(" + timeStr + " remaining)"));
        } else {
            player.sendSystemMessage(Component.literal("§7Idle — throw meat to begin gestation."));
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // use a normal block model — make it visually distinct
    }
}
