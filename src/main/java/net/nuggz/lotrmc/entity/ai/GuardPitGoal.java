package net.nuggz.lotrmc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

import java.util.EnumSet;

/**
 * Keeps the orc within GUARD_PIT_RADIUS blocks of its pit core.
 * Active when the squad order is GUARD_PIT.
 *
 * When the orc strays too far, this goal moves it back toward the pit.
 * It does not interfere with combat — the orc will fight if attacked
 * and return to position when combat ends.
 */
public class GuardPitGoal extends Goal {

    private final OrcEntity orc;
    private BlockPos targetPos = null;
    private int recalcCooldown = 0;

    public GuardPitGoal(OrcEntity orc) {
        this.orc = orc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (orc.getTarget() != null) return false; // don't interfere with combat
        if (orc.getPitPos() == null) return false;

        SquadOrderData orders = getOrders();
        if (orders == null || orders.getCurrentOrder() != OrcOrder.GUARD_PIT) return false;

        // Only activate if orc is outside the guard radius
        return orc.blockPosition().distSqr(orc.getPitPos())
                > SquadOrderData.GUARD_PIT_RADIUS * SquadOrderData.GUARD_PIT_RADIUS;
    }

    @Override
    public boolean canContinueToUse() {
        if (orc.getTarget() != null) return false;
        SquadOrderData orders = getOrders();
        if (orders == null || orders.getCurrentOrder() != OrcOrder.GUARD_PIT) return false;

        // Stop when back inside radius
        return orc.blockPosition().distSqr(orc.getPitPos())
                > (SquadOrderData.GUARD_PIT_RADIUS / 2.0)
                * (SquadOrderData.GUARD_PIT_RADIUS / 2.0);
    }

    @Override
    public void start() {
        navigateToPit();
    }

    @Override
    public void tick() {
        if (--recalcCooldown <= 0) navigateToPit();
    }

    @Override
    public void stop() {
        orc.getNavigation().stop();
    }

    private void navigateToPit() {
        BlockPos pit = orc.getPitPos();
        if (pit == null) return;

        // Navigate to a random point within the pit radius rather than
        // the exact center so orcs spread out instead of stacking
        double angle = orc.getRandom().nextDouble() * Math.PI * 2;
        double r     = orc.getRandom().nextDouble() * (SquadOrderData.GUARD_PIT_RADIUS / 2.0);
        double tx    = pit.getX() + Math.cos(angle) * r;
        double tz    = pit.getZ() + Math.sin(angle) * r;

        orc.getNavigation().moveTo(tx, pit.getY(), tz, 1.0);
        recalcCooldown = 60;
    }

    private SquadOrderData getOrders() {
        if (orc.getPitPos() == null) return null;
        if (!(orc.level().getBlockEntity(orc.getPitPos()) instanceof MudpitBlockEntity pit))
            return null;
        return pit.getSquadOrders();
    }
}