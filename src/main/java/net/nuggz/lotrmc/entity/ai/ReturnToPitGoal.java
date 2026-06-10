package net.nuggz.lotrmc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nuggz.lotrmc.entity.OrcEntity;
import net.nuggz.lotrmc.entity.order.OrcOrder;
import net.nuggz.lotrmc.entity.order.SquadOrderData;
import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;

import java.util.EnumSet;

/**
 * High-priority recall goal — moves orc back to its pit immediately.
 * Active when squad order is RETURN_TO_PIT.
 *
 * Drops current target and ignores combat until back at the pit,
 * at which point the order automatically reverts to GUARD_PIT.
 */
public class ReturnToPitGoal extends Goal {

    private final OrcEntity orc;
    private int recalcCooldown = 0;

    public ReturnToPitGoal(OrcEntity orc) {
        this.orc = orc;
        // High priority — interrupts movement AND targeting
        setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        SquadOrderData orders = getOrders();
        return orders != null
                && orders.getCurrentOrder() == OrcOrder.RETURN_TO_PIT
                && orc.getPitPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() && !hasReachedPit();
    }

    @Override
    public void start() {
        orc.setTarget(null); // drop current target
        navigateToPit();
    }

    @Override
    public void tick() {
        recalcCooldown--;
        if (recalcCooldown <= 0) navigateToPit();

        if (hasReachedPit()) {
            // Arrived — revert order to GUARD_PIT
            SquadOrderData orders = getOrders();
            if (orders != null) orders.setOrder(OrcOrder.GUARD_PIT);
            MudpitBlockEntity pit = getPit();
            if (pit != null) pit.setChanged();
        }
    }

    @Override
    public void stop() {
        orc.getNavigation().stop();
    }

    private void navigateToPit() {
        BlockPos pit = orc.getPitPos();
        if (pit == null) return;
        orc.getNavigation().moveTo(pit.getX() + 0.5, pit.getY(), pit.getZ() + 0.5, 1.2);
        recalcCooldown = 40;
    }

    private boolean hasReachedPit() {
        if (orc.getPitPos() == null) return true;
        return orc.blockPosition().closerThan(orc.getPitPos(), SquadOrderData.GUARD_PIT_RADIUS);
    }

    private SquadOrderData getOrders() {
        MudpitBlockEntity pit = getPit();
        return pit != null ? pit.getSquadOrders() : null;
    }

    private MudpitBlockEntity getPit() {
        if (orc.getPitPos() == null) return null;
        if (orc.level().getBlockEntity(orc.getPitPos()) instanceof MudpitBlockEntity pit)
            return pit;
        return null;
    }
}