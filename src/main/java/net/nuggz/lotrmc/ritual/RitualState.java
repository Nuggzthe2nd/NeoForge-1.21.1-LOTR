package net.nuggz.lotrmc.ritual;

import net.nuggz.lotrmc.LotrMC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * Persists all ritual state across server restarts.
 *
 * Stored on the overworld so it's always accessible regardless
 * of which dimension the player is in.
 */
public class RitualState extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_ritual";

    // --- Active ritual ---
    private boolean ritualActive = false;
    private UUID ritualistUUID = null;     // player who started the ritual
    private BlockPos altarCenter = null;   // soul sand position
    private long ritualStartDay = -1;      // game day the ritual began
    private int lastStormDay = -1;         // which day's storm has already fired (0-4)

    // --- Cooldown ---
    // If > 0, no ritual can be started. Counts down in game days.
    private long cooldownEndDay = -1;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static RitualState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RitualState::new, RitualState::load),
                DATA_NAME
        );
    }

    // -------------------------------------------------------------------------
    // Ritual lifecycle
    // -------------------------------------------------------------------------

    public boolean isRitualActive() { return ritualActive; }

    public boolean isOnCooldown(ServerLevel level) {
        if (cooldownEndDay < 0) return false;
        return getCurrentDay(level) < cooldownEndDay;
    }

    public long getCooldownDaysRemaining(ServerLevel level) {
        if (!isOnCooldown(level)) return 0;
        return cooldownEndDay - getCurrentDay(level);
    }

    /**
     * Start the ritual. Call after altar validation passes.
     */
    public void startRitual(UUID player, BlockPos center, ServerLevel level) {
        this.ritualActive = true;
        this.ritualistUUID = player;
        this.altarCenter = center;
        this.ritualStartDay = getCurrentDay(level);
        this.lastStormDay = -1;
        setDirty();
    }

    /**
     * Called when ritual completes successfully (day 5 storm finishes).
     * Does NOT call MudlandsManager — do that in RitualServerEvents.
     */
    public void completeRitual() {
        ritualActive = false;
        // No cooldown on success — there's now a Sauron which blocks re-ritual
        clearRitualFields();
        setDirty();
    }

    /**
     * Called when the altar is destroyed or otherwise interrupted.
     * Starts the 30 game-day cooldown for all players.
     */
    public void failRitual(ServerLevel level) {
        ritualActive = false;
        cooldownEndDay = getCurrentDay(level) + 30;
        clearRitualFields();
        setDirty();
    }

    /**
     * Which ritual day we're on (0 = first day, 4 = fifth/final day).
     * Returns -1 if no ritual is active.
     */
    public int getRitualDay(ServerLevel level) {
        if (!ritualActive) return -1;
        return (int) Math.min(getCurrentDay(level) - ritualStartDay, 4);
    }

    /**
     * Returns true if the storm event for the given ritual day
     * has not yet been fired this ritual.
     */
    public boolean shouldFireStorm(int ritualDay) {
        return ritualActive && ritualDay > lastStormDay;
    }

    public void markStormFired(int ritualDay) {
        lastStormDay = ritualDay;
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getRitualistUUID() { return ritualistUUID; }
    public BlockPos getAltarCenter() { return altarCenter; }
    public long getRitualStartDay() { return ritualStartDay; }

    // Debug-only helpers — used by MudlandsDebugCommand
    public void debugSetStartDay(long startDay) {
        this.ritualStartDay = startDay;
        setDirty();
    }

    public void debugClearCooldown() {
        this.cooldownEndDay = -1;
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long getCurrentDay(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private void clearRitualFields() {
        ritualistUUID = null;
        altarCenter = null;
        ritualStartDay = -1;
        lastStormDay = -1;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("RitualActive", ritualActive);
        tag.putLong("CooldownEndDay", cooldownEndDay);

        if (ritualistUUID != null)  tag.putUUID("RitualistUUID", ritualistUUID);
        if (altarCenter != null)    tag.putLong("AltarCenter", altarCenter.asLong());
        tag.putLong("RitualStartDay", ritualStartDay);
        tag.putInt("LastStormDay", lastStormDay);

        return tag;
    }

    public static RitualState load(CompoundTag tag, HolderLookup.Provider provider) {
        RitualState state = new RitualState();
        state.ritualActive    = tag.getBoolean("RitualActive");
        state.cooldownEndDay  = tag.getLong("CooldownEndDay");
        state.ritualStartDay  = tag.getLong("RitualStartDay");
        state.lastStormDay    = tag.getInt("LastStormDay");

        if (tag.hasUUID("RitualistUUID"))
            state.ritualistUUID = tag.getUUID("RitualistUUID");
        if (tag.contains("AltarCenter"))
            state.altarCenter = BlockPos.of(tag.getLong("AltarCenter"));

        return state;
    }
}