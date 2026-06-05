package net.nuggz.lotrmc.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * Holds the leader stats for a branded orc.
 * Rolled once at branding time and stored in the orc's NBT.
 *
 * Stats are 1-10. Each stat independently rolled with a slight
 * bias toward the middle (average of two d10 rolls) so extreme
 * values are rarer and feel meaningful when they appear.
 *
 * Strength  — increases the leader's own damage and boosts all
 *             orcs in the party's damage output.
 * Tactics   — increases raid survival chance, scar gain rate,
 *             and will eventually unlock battle tactics.
 * Presence  — grants passive buffs to nearby orcs (damage, morale),
 *             radius scales with stat value.
 */
public class OrcLeaderData {

    public final int strength;
    public final int tactics;
    public final int presence;

    private OrcLeaderData(int strength, int tactics, int presence) {
        this.strength = strength;
        this.tactics  = tactics;
        this.presence = presence;
    }

    // -------------------------------------------------------------------------
    // Rolling
    // -------------------------------------------------------------------------

    /**
     * Roll a fresh set of leader stats.
     * Uses average-of-two to bias toward middle values (3-7 most common).
     */
    public static OrcLeaderData roll(RandomSource rng) {
        return new OrcLeaderData(
                rollStat(rng),
                rollStat(rng),
                rollStat(rng)
        );
    }

    /** Roll one stat: average of two 1-10 rolls, rounded, clamped 1-10. */
    private static int rollStat(RandomSource rng) {
        int a = rng.nextInt(10) + 1;
        int b = rng.nextInt(10) + 1;
        return Math.round((a + b) / 2.0f);
    }

    // -------------------------------------------------------------------------
    // Derived values — used by raid simulation and combat
    // -------------------------------------------------------------------------

    /** Flat damage bonus applied to all orcs in this leader's party. */
    public float partyDamageBonus() {
        return (strength - 1) * 0.3f; // 0 at str 1, +2.7 at str 10
    }

    /** Multiplier on raid survival chance (tactics). 1.0 = no bonus. */
    public float raidSurvivalMultiplier() {
        return 1.0f + (tactics - 1) * 0.08f; // 1.0 at tac 1, 1.72 at tac 10
    }

    /** Bonus scars rolled after a raid (tactics). */
    public int bonusScarRolls() {
        return tactics / 4; // 0 at tac 1-3, 1 at 4-7, 2 at 8-10
    }

    /** Radius in blocks that Presence affects nearby orcs. */
    public double presenceRadius() {
        return 4.0 + (presence - 1) * 1.5; // 4 blocks at pres 1, 17.5 at pres 10
    }

    /** Damage multiplier applied to orcs within presence radius. */
    public float presenceDamageMultiplier() {
        return 1.0f + (presence - 1) * 0.05f; // 1.0 at pres 1, 1.45 at pres 10
    }

    // -------------------------------------------------------------------------
    // Display
    // -------------------------------------------------------------------------

    /** Returns a formatted string for chat display when branding is confirmed. */
    public String toDisplayString() {
        return "§4Strength: §c" + statBar(strength)
                + "\n§2Tactics:  §a" + statBar(tactics)
                + "\n§5Presence: §d" + statBar(presence);
    }

    private static String statBar(int value) {
        // e.g. value 7 → "███████░░░ (7)"
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) bar.append(i < value ? "█" : "░");
        return bar + " (" + value + ")";
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Strength", strength);
        tag.putInt("Tactics",  tactics);
        tag.putInt("Presence", presence);
        return tag;
    }

    public static OrcLeaderData load(CompoundTag tag) {
        return new OrcLeaderData(
                tag.getInt("Strength"),
                tag.getInt("Tactics"),
                tag.getInt("Presence")
        );
    }

    public static boolean isPresent(CompoundTag tag) {
        return tag.contains("Strength");
    }
}