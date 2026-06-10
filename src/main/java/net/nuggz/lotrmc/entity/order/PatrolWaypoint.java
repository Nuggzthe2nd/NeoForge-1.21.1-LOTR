package net.nuggz.lotrmc.entity.order;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A single waypoint in a patrol path.
 *
 * Waypoints come in two types:
 *   WAYPOINT   — orcs pass through without stopping
 *   WATCH_POST — orcs wait here for the squad's configured wait time
 *
 * Created by the player using sneak + right-click with the Nethercrown.
 * Double-clicking (right-clicking an already-placed waypoint block) upgrades
 * it to a watch post.
 */
public class PatrolWaypoint {

    public enum Type { WAYPOINT, WATCH_POST }

    public final BlockPos pos;
    public final Type type;

    public PatrolWaypoint(BlockPos pos, Type type) {
        this.pos  = pos;
        this.type = type;
    }

    public boolean isWatchPost() { return type == Type.WATCH_POST; }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos",  pos.asLong());
        tag.putString("Type", type.name());
        return tag;
    }

    public static PatrolWaypoint load(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        Type type;
        try { type = Type.valueOf(tag.getString("Type")); }
        catch (Exception e) { type = Type.WAYPOINT; }
        return new PatrolWaypoint(pos, type);
    }

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(type.name());
    }

    public static PatrolWaypoint decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        Type type;
        try { type = Type.valueOf(buf.readUtf()); }
        catch (Exception e) { type = Type.WAYPOINT; }
        return new PatrolWaypoint(pos, type);
    }
}