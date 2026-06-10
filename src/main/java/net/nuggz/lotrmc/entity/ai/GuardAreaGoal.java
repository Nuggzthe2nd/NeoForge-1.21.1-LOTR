package net.nuggz.lotrmc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

import java.util.EnumSet;

/**
 * Keeps the orc within a configured radius of a configured center position.
 * Active when the squad order is GUARD_AREA.
 *
 * Behaves identically to GuardPitGoal but uses the squad's
 * configured guardCenter and guardRadius instead of the pit position.
 */
public class GuardAreaGoal extends Goal {

    private final OrcEntity orc;
    private int recalcCooldown = 0;

    public GuardAreaGoal(OrcEntity orc) {
        this.orc = orc;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (orc.getTarget() != null) return false;
        SquadOrderData orders = getOrders();
        if (orders == null || orders.getCurrentOrder() != OrcOrder.GUARD_AREA) return false;
        if (orders.getGuardCenter() == null) return false;

        double r = orders.getGuardRadius();
        return orc.blockPosition().distSqr(orders.getGuardCenter()) > r * r;
    }

    @Override
    public boolean canContinueToUse() {
        if (orc.getTarget() != null) return false;
        SquadOrderData orders = getOrders();
        if (orders == null || orders.getCurrentOrder() != OrcOrder.GUARD_AREA) return false;
        if (orders.getGuardCenter() == null) return false;

        double r = orders.getGuardRadius() / 2.0;
        return orc.blockPosition().distSqr(orders.getGuardCenter()) > r * r;
    }

    @Override
    public void start() { navigateToArea(); }

    @Override
    public void tick() {
        if (--recalcCooldown <= 0) navigateToArea();
    }

    @Override
    public void stop() { orc.getNavigation().stop(); }

    private void navigateToArea() {
        SquadOrderData orders = getOrders();
        if (orders == null || orders.getGuardCenter() == null) return;

        BlockPos center = orders.getGuardCenter();
        double angle = orc.getRandom().nextDouble() * Math.PI * 2;
        double r     = orc.getRandom().nextDouble() * (orders.getGuardRadius() / 2.0);
        double tx    = center.getX() + Math.cos(angle) * r;
        double tz    = center.getZ() + Math.sin(angle) * r;

        orc.getNavigation().moveTo(tx, center.getY(), tz, 1.0);
        recalcCooldown = 60;
    }

    private SquadOrderData getOrders() {
        if (orc.getPitPos() == null) return null;
        if (!(orc.level().getBlockEntity(orc.getPitPos()) instanceof MudpitBlockEntity pit))
            return null;
        return pit.getSquadOrders();
    }
}