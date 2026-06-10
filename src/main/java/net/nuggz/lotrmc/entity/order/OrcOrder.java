package net.nuggz.lotrmc.entity.order;

/**
 * All possible orders a squad (pit) can be assigned.
 *
 * Stored in MudpitBlockEntity and applied to all orcs from that pit.
 * Individual orcs follow the squad order unless overridden by an
 * active command (attack-move, return, etc.).
 */
public enum OrcOrder {

    /** Stay within GUARD_PIT_RADIUS blocks of the pit core. Default order. */
    GUARD_PIT,

    /** Stay within a configured radius of a configured center position. */
    GUARD_AREA,

    /** Walk between configured waypoints, waiting at watch posts. */
    PATROL,

    /** Override all orders — return to pit immediately. */
    RETURN_TO_PIT
}
