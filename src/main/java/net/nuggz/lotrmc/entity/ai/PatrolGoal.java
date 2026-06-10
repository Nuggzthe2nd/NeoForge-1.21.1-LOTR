package net.nuggz.lotrmc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.PatrolWaypoint;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

import java.util.EnumSet;
import java.util.List;

/**
 * Moves the orc along a configured patrol path of waypoints.
 *
 * Behavior:
 *   - Walks to each waypoint in order, looping back to the first
 *   - At WATCH_POST waypoints, waits for watchPostWaitTicks before moving on
 *   - When entering combat, remembers the last waypoint and returns there
 *     with the whole squad after combat ends
 *   - Rallies all orcs within RALLY_RADIUS blocks when combat begins
 *
 * State machine:
 *   MOVING   → navigating toward current waypoint
 *   WAITING  → standing at a watch post, counting down wait timer
 *   RALLYING → in combat, will return to last waypoint when done
 */
public class PatrolGoal extends Goal {

    private enum State { MOVING, WAITING, RETURNING_AFTER_COMBAT }

    private final OrcEntity orc;

    private int currentWaypointIndex = 0;
    private int lastWaypointIndex    = 0;
    private int waitTicksRemaining   = 0;
    private State state              = State.MOVING;
    private int recalcCooldown       = 0;
    private boolean wasInCombat      = false;

    public PatrolGoal(OrcEntity orc) {
        this.orc = orc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal eligibility
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        SquadOrderData orders = getOrders();
        if (orders == null) return false;
        if (orders.getCurrentOrder() != OrcOrder.PATROL) return false;
        return orders.hasValidPatrolPath();
    }

    @Override
    public boolean canContinueToUse() {
        SquadOrderData orders = getOrders();
        if (orders == null) return false;
        if (orders.getCurrentOrder() != OrcOrder.PATROL) return false;
        return orders.hasValidPatrolPath();
    }

    // -------------------------------------------------------------------------
    // Tick logic
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        state              = State.MOVING;
        recalcCooldown     = 0;
        wasInCombat        = false;
        navigateToCurrentWaypoint();
    }

    @Override
    public void stop() {
        orc.getNavigation().stop();
    }

    @Override
    public void tick() {
        SquadOrderData orders = getOrders();
        if (orders == null) return;

        List<PatrolWaypoint> waypoints = orders.getWaypoints();
        if (waypoints.isEmpty()) return;

        boolean inCombat = orc.getTarget() != null;

        // --- Combat detection ---
        if (inCombat && !wasInCombat) {
            // Just entered combat — rally nearby orcs
            rallyNearbyOrcs();
            lastWaypointIndex = currentWaypointIndex;
            wasInCombat = true;
            state = State.RETURNING_AFTER_COMBAT;
        }

        if (!inCombat && wasInCombat) {
            // Just left combat — return to last waypoint
            wasInCombat = false;
            state = State.RETURNING_AFTER_COMBAT;
            navigateToWaypoint(waypoints.get(lastWaypointIndex));
            return;
        }

        // Don't move while in combat
        if (inCombat) return;

        // --- Normal patrol logic ---
        switch (state) {
            case RETURNING_AFTER_COMBAT -> {
                // Check if we've reached the last waypoint
                if (hasReachedWaypoint(waypoints.get(lastWaypointIndex))) {
                    currentWaypointIndex = lastWaypointIndex;
                    state = State.MOVING;
                    navigateToCurrentWaypoint();
                } else {
                    // Keep navigating
                    recalcCooldown--;
                    if (recalcCooldown <= 0) {
                        navigateToWaypoint(waypoints.get(lastWaypointIndex));
                    }
                }
            }

            case MOVING -> {
                PatrolWaypoint current = waypoints.get(currentWaypointIndex);

                if (hasReachedWaypoint(current)) {
                    if (current.isWatchPost()) {
                        // Start waiting
                        state = State.WAITING;
                        waitTicksRemaining = orders.getWatchPostWaitTicks();
                        orc.getNavigation().stop();
                    } else {
                        // Move to next waypoint immediately
                        advanceWaypoint(waypoints.size());
                        navigateToCurrentWaypoint();
                    }
                } else {
                    recalcCooldown--;
                    if (recalcCooldown <= 0) {
                        navigateToCurrentWaypoint();
                    }
                }
            }

            case WAITING -> {
                waitTicksRemaining--;
                if (waitTicksRemaining <= 0) {
                    // Done waiting — move to next waypoint
                    state = State.MOVING;
                    advanceWaypoint(waypoints.size());
                    navigateToCurrentWaypoint();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    private void navigateToCurrentWaypoint() {
        SquadOrderData orders = getOrders();
        if (orders == null) return;
        List<PatrolWaypoint> waypoints = orders.getWaypoints();
        if (currentWaypointIndex >= waypoints.size()) currentWaypointIndex = 0;
        navigateToWaypoint(waypoints.get(currentWaypointIndex));
    }

    private void navigateToWaypoint(PatrolWaypoint wp) {
        orc.getNavigation().moveTo(
                wp.pos.getX() + 0.5,
                wp.pos.getY(),
                wp.pos.getZ() + 0.5,
                1.0);
        recalcCooldown = 60;
    }

    private boolean hasReachedWaypoint(PatrolWaypoint wp) {
        return orc.blockPosition().closerThan(wp.pos, 2.5);
    }

    private void advanceWaypoint(int total) {
        currentWaypointIndex = (currentWaypointIndex + 1) % total;
    }

    // -------------------------------------------------------------------------
    // Rally
    // -------------------------------------------------------------------------

    /**
     * Alert all orcs within RALLY_RADIUS to come help in combat.
     * Affects all orcs regardless of their current order.
     */
    private void rallyNearbyOrcs() {
        LivingEntity target = orc.getTarget();
        if (target == null) return;

        double r = SquadOrderData.RALLY_RADIUS;
        AABB searchBox = orc.getBoundingBox().inflate(r);

        orc.level().getEntitiesOfClass(OrcEntity.class, searchBox, other -> {
            if (other == orc) return false;
            if (other.getTarget() != null) return false;
            // Only rally orcs from the same pit
            return orc.getPitPos() != null
                    && orc.getPitPos().equals(other.getPitPos());
        }).forEach(ally -> ally.setTarget(target));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SquadOrderData getOrders() {
        if (orc.getPitPos() == null) return null;
        if (!(orc.level().getBlockEntity(orc.getPitPos()) instanceof MudpitBlockEntity pit))
            return null;
        return pit.getSquadOrders();
    }
}