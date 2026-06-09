package net.nuggz.lotrmc.wartable;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.nuggz.lotrmc.network.ModNetwork;
import net.nuggz.lotrmc.registry.ModBlockEntities;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;

import javax.annotation.Nullable;

/**
 * The War Table block — the converted center of the multiblock structure.
 *
 * Placed automatically when a player right-clicks a cartographer's table
 * that is surrounded by 8 obsidian blocks (handled in ModBlockEvents).
 *
 * Right-clicking opens the War Table UI via WarTableOpenPacket.
 *
 * Every 40 ticks checks if the obsidian structure is still intact.
 * If not, reverts to a cartographer's table (inert).
 *
 * Only usable by Sauron or lieutenants.
 */
public class WarTableBlock extends BaseEntityBlock {

    public static final MapCodec<WarTableBlock> CODEC = MapCodec.unit(WarTableBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    private static final int INTEGRITY_CHECK_INTERVAL = 40;

    public WarTableBlock() {
        super(BlockBehaviour.Properties.ofFullCopy(Blocks.CARTOGRAPHY_TABLE)
                .mapColor(MapColor.COLOR_BLACK)
                .lightLevel(state -> 3));
    }

    // -------------------------------------------------------------------------
    // Right-click — open UI
    // -------------------------------------------------------------------------

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level,
                                            BlockPos pos, Player player,
                                            BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer serverPlayer = (ServerPlayer) player;

        // Check faction
        MudlandsChunkData data = MudlandsChunkData.get(serverLevel);
        boolean isFaction = player.getUUID().equals(data.getSauronUUID())
                || data.isLieutenant(player.getUUID());
        if (!isFaction) {
            player.sendSystemMessage(Component.literal(
                    "§cOnly the dark lord or his lieutenants may use this."));
            return InteractionResult.FAIL;
        }

        // Check structure still intact
        if (!WarTableStructureValidator.isValid(level, pos)) {
            player.sendSystemMessage(Component.literal(
                    "§cThe War Table structure is incomplete: "
                            + WarTableStructureValidator.diagnose(level, pos)));
            return InteractionResult.FAIL;
        }

        // Send packet to open UI on client
        ModNetwork.openWarTable(serverPlayer, pos);
        return InteractionResult.CONSUME;
    }

    // -------------------------------------------------------------------------
    // Block entity / ticking
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WarTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.WAR_TABLE.get(),
                (lvl, pos, st, be) -> ((WarTableBlockEntity) be).serverTick((ServerLevel) lvl, pos));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // -------------------------------------------------------------------------
    // Revert to cartographer's table if structure broken
    // Called from WarTableBlockEntity.serverTick()
    // -------------------------------------------------------------------------

    public static void checkIntegrity(ServerLevel level, BlockPos pos) {
        if (!WarTableStructureValidator.isValid(level, pos)) {
            level.setBlock(pos,
                    Blocks.CARTOGRAPHY_TABLE.defaultBlockState(),
                    3);
            // No drops — the obsidian is still there, just the magic is gone
        }
    }
}