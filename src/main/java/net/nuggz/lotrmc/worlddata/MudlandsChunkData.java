package net.nuggz.lotrmc.worlddata;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.nuggz.lotrmc.LotrMC;

import java.util.*;

public class MudlandsChunkData extends SavedData {

    private static final String DATA_NAME = LotrMC.MODID + "_mudlands";

    // --- Sauron ---
    // Null means no active Sauron in this world
    private UUID sauronUUID = null;
    private BlockPos sauronOrigin = null; // chunk center where the ritual was performed
    private int sauronRadius = 0;         // current mudlands radius in chunks

    // --- Converted chunks ---
    // All chunks currently mudlands, mapped chunk pos long -> owner UUID (sauron or a lieutenant)
    private final Map<Long, UUID> convertedChunks = new HashMap<>();

    // --- Lieutenants ---
    // Lieutenant UUID -> their origin chunk pos (as long)
    private final Map<UUID, Long> lieutenantOrigins = new HashMap<>();
    // Lieutenant UUID -> their current radius
    private final Map<UUID, Integer> lieutenantRadii = new HashMap<>();

    // --- Collapse state ---
    private boolean collapseActive = false;
    private long collapseStartGameTime = -1;

    // -------------------------------------------------------------------------
    // Factory / loading
    // -------------------------------------------------------------------------

    public static MudlandsChunkData get(ServerLevel level) {
        // Always load from the overworld so the data persists regardless of
        // which dimension the player is currently in.
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        MudlandsChunkData::new,
                        MudlandsChunkData::load
                ),
                DATA_NAME
        );
    }

    // -------------------------------------------------------------------------
    // Sauron management
    // -------------------------------------------------------------------------

    public boolean hasSauron() {
        return sauronUUID != null;
    }

    public UUID getSauronUUID() {
        return sauronUUID;
    }

    public BlockPos getSauronOrigin() {
        return sauronOrigin;
    }

    public int getSauronRadius() {
        return sauronRadius;
    }

    /** Called when a player completes the Nethercrown ritual. */
    public void setSauron(UUID playerUUID, BlockPos ritualPos) {
        this.sauronUUID = playerUUID;
        this.sauronOrigin = ritualPos;
        this.sauronRadius = 3; // starting radius in chunks
        this.collapseActive = false;
        this.collapseStartGameTime = -1;
        setDirty();
    }

    public void clearSauron() {
        this.sauronUUID = null;
        this.sauronOrigin = null;
        this.sauronRadius = 0;
        setDirty();
    }

    /** Expand Sauron's mudlands by one chunk ring. Returns the new radius. */
    public int expandSauronRadius() {
        sauronRadius++;
        setDirty();
        return sauronRadius;
    }

    /** Shrink Sauron's mudlands by one chunk ring during collapse. */
    public int shrinkSauronRadius() {
        if (sauronRadius > 0) sauronRadius--;
        setDirty();
        return sauronRadius;
    }

    // -------------------------------------------------------------------------
    // Converted chunk tracking
    // -------------------------------------------------------------------------

    /**
     * Mark a chunk as part of the mudlands, owned by the given UUID.
     * ownerUUID is either the Sauron UUID or a lieutenant UUID.
     */
    public void markChunkConverted(ChunkPos pos, UUID ownerUUID) {
        convertedChunks.put(pos.toLong(), ownerUUID);
        setDirty();
    }

    public void unmarkChunk(ChunkPos pos) {
        convertedChunks.remove(pos.toLong());
        setDirty();
    }

    public boolean isConverted(ChunkPos pos) {
        return convertedChunks.containsKey(pos.toLong());
    }

    public UUID getChunkOwner(ChunkPos pos) {
        return convertedChunks.get(pos.toLong());
    }

    /**
     * Returns all converted chunks owned by the given UUID,
     * sorted from outermost (farthest from origin) inward.
     * Used during collapse to revert chunks outermost-first.
     */
    public List<ChunkPos> getChunksSortedOutward(UUID ownerUUID, BlockPos origin) {
        ChunkPos originChunk = new ChunkPos(origin);
        List<ChunkPos> owned = new ArrayList<>();

        for (Map.Entry<Long, UUID> entry : convertedChunks.entrySet()) {
            if (ownerUUID.equals(entry.getValue())) {
                owned.add(new ChunkPos(entry.getKey()));
            }
        }

        // Sort by Chebyshev distance descending (outermost first)
        owned.sort((a, b) -> {
            int distA = chebyshev(a, originChunk);
            int distB = chebyshev(b, originChunk);
            return Integer.compare(distB, distA); // descending
        });

        return owned;
    }

    private int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    // -------------------------------------------------------------------------
    // Lieutenant management
    // -------------------------------------------------------------------------

    public void addLieutenant(UUID lieutenantUUID, BlockPos outpostPos) {
        lieutenantOrigins.put(lieutenantUUID, new ChunkPos(outpostPos).toLong());
        lieutenantRadii.put(lieutenantUUID, 1); // lieutenants start with radius 1
        setDirty();
    }

    public void removeLieutenant(UUID lieutenantUUID) {
        lieutenantOrigins.remove(lieutenantUUID);
        lieutenantRadii.remove(lieutenantUUID);
        setDirty();
    }

    public boolean isLieutenant(UUID uuid) {
        return lieutenantOrigins.containsKey(uuid);
    }

    public Set<UUID> getLieutenants() {
        return Collections.unmodifiableSet(lieutenantOrigins.keySet());
    }

    public ChunkPos getLieutenantOrigin(UUID lieutenantUUID) {
        Long packed = lieutenantOrigins.get(lieutenantUUID);
        return packed != null ? new ChunkPos(packed) : null;
    }

    public int getLieutenantRadius(UUID lieutenantUUID) {
        return lieutenantRadii.getOrDefault(lieutenantUUID, 0);
    }

    /**
     * The maximum radius a lieutenant is allowed, which is always
     * floor(sauronRadius / 2). This is enforced at expansion time.
     */
    public int getMaxLieutenantRadius() {
        return Math.max(1, sauronRadius / 2);
    }

    public boolean expandLieutenantRadius(UUID lieutenantUUID) {
        int current = getLieutenantRadius(lieutenantUUID);
        if (current >= getMaxLieutenantRadius()) return false; // blocked by Sauron cap
        lieutenantRadii.put(lieutenantUUID, current + 1);
        setDirty();
        return true;
    }

    /** Called when Sauron shrinks — clamps all lieutenant radii to the new cap. */
    public void clampLieutenantRadii() {
        int cap = getMaxLieutenantRadius();
        for (UUID uuid : lieutenantRadii.keySet()) {
            if (lieutenantRadii.get(uuid) > cap) {
                lieutenantRadii.put(uuid, cap);
            }
        }
        setDirty();
    }

    // -------------------------------------------------------------------------
    // Collapse state
    // -------------------------------------------------------------------------

    public boolean isCollapseActive() {
        return collapseActive;
    }

    public void startCollapse(long currentGameTime) {
        this.collapseActive = true;
        this.collapseStartGameTime = currentGameTime;
        setDirty();
    }

    public void stopCollapse() {
        this.collapseActive = false;
        this.collapseStartGameTime = -1;
        setDirty();
    }

    public long getCollapseStartGameTime() {
        return collapseStartGameTime;
    }

    // -------------------------------------------------------------------------
    // NBT serialization
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        // Sauron
        if (sauronUUID != null) {
            tag.putUUID("SauronUUID", sauronUUID);
            tag.putLong("SauronOrigin", sauronOrigin.asLong());
            tag.putInt("SauronRadius", sauronRadius);
        }

        // Converted chunks
        ListTag chunkList = new ListTag();
        for (Map.Entry<Long, UUID> entry : convertedChunks.entrySet()) {
            CompoundTag ct = new CompoundTag();
            ct.putLong("Pos", entry.getKey());
            ct.putUUID("Owner", entry.getValue());
            chunkList.add(ct);
        }
        tag.put("ConvertedChunks", chunkList);

        // Lieutenants
        ListTag lieutList = new ListTag();
        for (UUID uuid : lieutenantOrigins.keySet()) {
            CompoundTag lt = new CompoundTag();
            lt.putUUID("UUID", uuid);
            lt.putLong("Origin", lieutenantOrigins.get(uuid));
            lt.putInt("Radius", lieutenantRadii.getOrDefault(uuid, 1));
            lieutList.add(lt);
        }
        tag.put("Lieutenants", lieutList);

        // Collapse
        tag.putBoolean("CollapseActive", collapseActive);
        tag.putLong("CollapseStart", collapseStartGameTime);

        return tag;
    }

    public static MudlandsChunkData load(CompoundTag tag, HolderLookup.Provider provider) {
        MudlandsChunkData data = new MudlandsChunkData();

        // Sauron
        if (tag.hasUUID("SauronUUID")) {
            data.sauronUUID = tag.getUUID("SauronUUID");
            data.sauronOrigin = BlockPos.of(tag.getLong("SauronOrigin"));
            data.sauronRadius = tag.getInt("SauronRadius");
        }

        // Converted chunks
        ListTag chunkList = tag.getList("ConvertedChunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunkList.size(); i++) {
            CompoundTag ct = chunkList.getCompound(i);
            data.convertedChunks.put(ct.getLong("Pos"), ct.getUUID("Owner"));
        }

        // Lieutenants
        ListTag lieutList = tag.getList("Lieutenants", Tag.TAG_COMPOUND);
        for (int i = 0; i < lieutList.size(); i++) {
            CompoundTag lt = lieutList.getCompound(i);
            UUID uuid = lt.getUUID("UUID");
            data.lieutenantOrigins.put(uuid, lt.getLong("Origin"));
            data.lieutenantRadii.put(uuid, lt.getInt("Radius"));
        }

        // Collapse
        data.collapseActive = tag.getBoolean("CollapseActive");
        data.collapseStartGameTime = tag.getLong("CollapseStart");

        return data;
    }
}