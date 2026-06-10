package net.nuggz.lotrmc.entity.order;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores all order configuration for a single pit's squad.
 *
 * Serialized into MudpitBlockEntity's NBT.
 *
 * Fields used per order type:
 *   GUARD_PIT    — no extra config needed (radius is a constant)
 *   GUARD_AREA   — guardCenter, guardRadius
 *   PATROL       — waypoints, watchPostWaitTicks
 *   RETURN_TO_PIT — no extra config
 */
public class SquadOrderData {

    public static final int MAX_WAYPOINTS       = 10;
    public static final int GUARD_PIT_RADIUS    = 24; // blocks
    public static final int RALLY_RADIUS        = 10; // blocks — combat alert radius
    public static final int DEFAULT_WATCH_TICKS = 600; // 30 seconds

    // Current order
    private OrcOrder currentOrder = OrcOrder.GUARD_PIT;

    // Guard Area config
    private BlockPos guardCenter = null;
    private int guardRadius      = 16;

    // Patrol config
    private final List<PatrolWaypoint> waypoints = new ArrayList<>();
    private int watchPostWaitTicks = DEFAULT_WATCH_TICKS;

    // -------------------------------------------------------------------------
    // Order management
    // -------------------------------------------------------------------------

    public OrcOrder getCurrentOrder() { return currentOrder; }

    public void setOrder(OrcOrder order) { this.currentOrder = order; }

    public String getDisplayName() {
        return switch (currentOrder) {
            case GUARD_PIT    -> "Guarding Pit";
            case GUARD_AREA   -> "Guarding Area";
            case PATROL       -> "Patrolling";
            case RETURN_TO_PIT -> "Returning";
        };
    }

    // -------------------------------------------------------------------------
    // Guard Area
    // -------------------------------------------------------------------------

    public BlockPos getGuardCenter()  { return guardCenter; }
    public int getGuardRadius()       { return guardRadius; }

    public void setGuardArea(BlockPos center, int radius) {
        this.guardCenter = center;
        this.guardRadius = radius;
        this.currentOrder = OrcOrder.GUARD_AREA;
    }

    /** Update only the center without changing the radius or current order. */
    public void setGuardCenter(BlockPos center) {
        this.guardCenter = center;
    }

    // -------------------------------------------------------------------------
    // Patrol
    // -------------------------------------------------------------------------

    public List<PatrolWaypoint> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    public int getWatchPostWaitTicks() { return watchPostWaitTicks; }

    public void setWatchPostWaitTicks(int ticks) {
        this.watchPostWaitTicks = ticks;
    }

    /**
     * Add a waypoint at the given position.
     * If a waypoint already exists at this position, upgrade it to a watch post.
     * If at max capacity, replace the oldest waypoint.
     */
    public void addOrUpgradeWaypoint(BlockPos pos) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).pos.equals(pos)) {
                waypoints.set(i, new PatrolWaypoint(pos, PatrolWaypoint.Type.WATCH_POST));
                return;
            }
        }

        if (waypoints.size() >= MAX_WAYPOINTS) {
            waypoints.remove(0);
        }
        waypoints.add(new PatrolWaypoint(pos, PatrolWaypoint.Type.WAYPOINT));
    }

    /** Replace the waypoint list wholesale, preserving types. Used when syncing from a packet. */
    public void setWaypoints(List<PatrolWaypoint> incoming) {
        waypoints.clear();
        for (PatrolWaypoint wp : incoming) {
            if (waypoints.size() >= MAX_WAYPOINTS) break;
            waypoints.add(wp);
        }
    }

    public void clearWaypoints() { waypoints.clear(); }

    public boolean hasValidPatrolPath() { return waypoints.size() >= 2; }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Order", currentOrder.name());
        tag.putInt("WatchPostWaitTicks", watchPostWaitTicks);
        tag.putInt("GuardRadius", guardRadius);

        if (guardCenter != null) tag.putLong("GuardCenter", guardCenter.asLong());

        ListTag wpList = new ListTag();
        for (PatrolWaypoint wp : waypoints) wpList.add(wp.save());
        tag.put("Waypoints", wpList);

        return tag;
    }

    public static SquadOrderData load(CompoundTag tag) {
        SquadOrderData data = new SquadOrderData();

        try { data.currentOrder = OrcOrder.valueOf(tag.getString("Order")); }
        catch (Exception e) { data.currentOrder = OrcOrder.GUARD_PIT; }

        data.watchPostWaitTicks = tag.contains("WatchPostWaitTicks")
                ? tag.getInt("WatchPostWaitTicks") : DEFAULT_WATCH_TICKS;
        data.guardRadius = tag.contains("GuardRadius")
                ? tag.getInt("GuardRadius") : 16;

        if (tag.contains("GuardCenter"))
            data.guardCenter = BlockPos.of(tag.getLong("GuardCenter"));

        ListTag wpList = tag.getList("Waypoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < wpList.size(); i++)
            data.waypoints.add(PatrolWaypoint.load(wpList.getCompound(i)));

        return data;
    }

    // -------------------------------------------------------------------------
    // Network (for squad orders UI packet)
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(currentOrder.name());
        buf.writeInt(watchPostWaitTicks);
        buf.writeInt(guardRadius);
        buf.writeBoolean(guardCenter != null);
        if (guardCenter != null) buf.writeLong(guardCenter.asLong());

        buf.writeInt(waypoints.size());
        for (PatrolWaypoint wp : waypoints) wp.encode(buf);
    }

    public static SquadOrderData decode(FriendlyByteBuf buf) {
        SquadOrderData data = new SquadOrderData();
        try { data.currentOrder = OrcOrder.valueOf(buf.readUtf()); }
        catch (Exception e) { data.currentOrder = OrcOrder.GUARD_PIT; }

        data.watchPostWaitTicks = buf.readInt();
        data.guardRadius        = buf.readInt();
        if (buf.readBoolean()) data.guardCenter = BlockPos.of(buf.readLong());

        int wpCount = buf.readInt();
        for (int i = 0; i < wpCount; i++)
            data.waypoints.add(PatrolWaypoint.decode(buf));

        return data;
    }
}