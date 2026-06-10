package net.nuggz.lotrmc.warmap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents an active raid party that has left the mudlands.
 *
 * Once orcs are sent on a raid they are removed as real entities
 * and replaced by this lightweight SavedData entry. The simulation
 * runs in RaidSimulator when the return timer expires.
 *
 * State machine:
 *   OUTBOUND  → traveling to target (departureTime → arrivalTime)
 *   AT_TARGET → simulating the raid (arrivalTime → returnTime)
 *   RETURNING → traveling back (returnTime → completionTime)
 *
 * The client interpolates the party's map marker position based on
 * elapsed time between origin and target.
 */
public class RaidParty {

    public enum RaidState { OUTBOUND, AT_TARGET, RETURNING }

    public enum TargetType { POI, FREE_TARGET }

    // Identity
    public final UUID partyId;
    public final int pitIndex;          // which pit this party came from
    public final List<UUID> orcUUIDs;   // orcs in this party (may have died)

    // Leader snapshot — stored so simulation works even if leader entity is gone
    public final String leaderName;
    public final int leaderStrength;
    public final int leaderTactics;
    public final int leaderPresence;
    public final int partyScarTotal;    // sum of all orc scars — affects simulation

    // Target
    public final int targetChunkX;
    public final int targetChunkZ;
    public final TargetType targetType;
    public final String targetLabel;    // "Plains Village", "Player: Steve", etc.

    // Origin (for map interpolation)
    public final int originChunkX;
    public final int originChunkZ;

    // Timing — all in game ticks
    public final long departureTime;
    public final long arrivalTime;      // when they reach target
    public final long returnTime;       // when they start heading back
    public final long completionTime;   // when they arrive home

    // Mutable state
    public RaidState state;

    public RaidParty(UUID partyId, int pitIndex, List<UUID> orcUUIDs,
                     String leaderName, int leaderStrength, int leaderTactics,
                     int leaderPresence, int partyScarTotal,
                     int targetChunkX, int targetChunkZ,
                     TargetType targetType, String targetLabel,
                     int originChunkX, int originChunkZ,
                     long departureTime, long arrivalTime,
                     long returnTime, long completionTime) {
        this.partyId        = partyId;
        this.pitIndex       = pitIndex;
        this.orcUUIDs       = new ArrayList<>(orcUUIDs);
        this.leaderName     = leaderName;
        this.leaderStrength = leaderStrength;
        this.leaderTactics  = leaderTactics;
        this.leaderPresence = leaderPresence;
        this.partyScarTotal = partyScarTotal;
        this.targetChunkX   = targetChunkX;
        this.targetChunkZ   = targetChunkZ;
        this.targetType     = targetType;
        this.targetLabel    = targetLabel;
        this.originChunkX   = originChunkX;
        this.originChunkZ   = originChunkZ;
        this.departureTime  = departureTime;
        this.arrivalTime    = arrivalTime;
        this.returnTime     = returnTime;
        this.completionTime = completionTime;
        this.state          = RaidState.OUTBOUND;
    }

    // -------------------------------------------------------------------------
    // Timing helpers
    // -------------------------------------------------------------------------

    /**
     * Travel time in ticks based on chunk distance.
     * 1 chunk = 20 ticks (1 second) of travel time — adjust as needed.
     */
    public static final int TICKS_PER_CHUNK_TRAVEL = 20;

    /**
     * Time spent at target simulating the raid.
     * Base 600 ticks (30s) + scales with party size.
     */
    public static final int BASE_AT_TARGET_TICKS = 600;
    public static final int TICKS_PER_ORC = 60;

    public static long calculateArrivalTime(long departure, int originX, int originZ,
                                            int targetX, int targetZ) {
        int chunkDist = Math.max(Math.abs(targetX - originX), Math.abs(targetZ - originZ));
        return departure + (long) chunkDist * TICKS_PER_CHUNK_TRAVEL;
    }

    public static long calculateReturnTime(long arrival, int partySize) {
        return arrival + BASE_AT_TARGET_TICKS + (long) partySize * TICKS_PER_ORC;
    }

    public static long calculateCompletionTime(long returnStart,
                                               int originX, int originZ,
                                               int targetX, int targetZ) {
        int chunkDist = Math.max(Math.abs(targetX - originX), Math.abs(targetZ - originZ));
        return returnStart + (long) chunkDist * TICKS_PER_CHUNK_TRAVEL;
    }

    /**
     * Get interpolated map position (in chunk coords) for display.
     * Returns fractional chunk coords for smooth marker movement.
     */
    public float[] getInterpolatedPosition(long currentTick) {
        switch (state) {
            case OUTBOUND -> {
                float t = (float)(currentTick - departureTime)
                        / (float)(arrivalTime - departureTime);
                t = Math.max(0, Math.min(1, t));
                return new float[] {
                        originChunkX + (targetChunkX - originChunkX) * t,
                        originChunkZ + (targetChunkZ - originChunkZ) * t
                };
            }
            case AT_TARGET -> {
                return new float[] { targetChunkX, targetChunkZ };
            }
            case RETURNING -> {
                float t = (float)(currentTick - returnTime)
                        / (float)(completionTime - returnTime);
                t = Math.max(0, Math.min(1, t));
                return new float[] {
                        targetChunkX + (originChunkX - targetChunkX) * t,
                        targetChunkZ + (originChunkZ - targetChunkZ) * t
                };
            }
        }
        return new float[] { originChunkX, originChunkZ };
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PartyId",        partyId);
        tag.putInt("PitIndex",        pitIndex);
        tag.putString("LeaderName",   leaderName != null ? leaderName : "");
        tag.putInt("LeaderStrength",  leaderStrength);
        tag.putInt("LeaderTactics",   leaderTactics);
        tag.putInt("LeaderPresence",  leaderPresence);
        tag.putInt("PartyScarTotal",  partyScarTotal);
        tag.putInt("TargetChunkX",    targetChunkX);
        tag.putInt("TargetChunkZ",    targetChunkZ);
        tag.putString("TargetType",   targetType.name());
        tag.putString("TargetLabel",  targetLabel != null ? targetLabel : "");
        tag.putInt("OriginChunkX",    originChunkX);
        tag.putInt("OriginChunkZ",    originChunkZ);
        tag.putLong("DepartureTime",  departureTime);
        tag.putLong("ArrivalTime",    arrivalTime);
        tag.putLong("ReturnTime",     returnTime);
        tag.putLong("CompletionTime", completionTime);
        tag.putString("State",        state.name());

        ListTag orcList = new ListTag();
        for (UUID uuid : orcUUIDs) {
            CompoundTag orcTag = new CompoundTag();
            orcTag.putUUID("UUID", uuid);
            orcList.add(orcTag);
        }
        tag.put("Orcs", orcList);
        return tag;
    }

    public static RaidParty load(CompoundTag tag) {
        List<UUID> orcs = new ArrayList<>();
        ListTag orcList = tag.getList("Orcs", Tag.TAG_COMPOUND);
        for (int i = 0; i < orcList.size(); i++) {
            orcs.add(orcList.getCompound(i).getUUID("UUID"));
        }

        TargetType targetType;
        try { targetType = TargetType.valueOf(tag.getString("TargetType")); }
        catch (Exception e) { targetType = TargetType.FREE_TARGET; }

        RaidParty party = new RaidParty(
                tag.getUUID("PartyId"),
                tag.getInt("PitIndex"),
                orcs,
                tag.getString("LeaderName"),
                tag.getInt("LeaderStrength"),
                tag.getInt("LeaderTactics"),
                tag.getInt("LeaderPresence"),
                tag.getInt("PartyScarTotal"),
                tag.getInt("TargetChunkX"),
                tag.getInt("TargetChunkZ"),
                targetType,
                tag.getString("TargetLabel"),
                tag.getInt("OriginChunkX"),
                tag.getInt("OriginChunkZ"),
                tag.getLong("DepartureTime"),
                tag.getLong("ArrivalTime"),
                tag.getLong("ReturnTime"),
                tag.getLong("CompletionTime")
        );

        try { party.state = RaidState.valueOf(tag.getString("State")); }
        catch (Exception e) { party.state = RaidState.OUTBOUND; }

        return party;
    }

    // -------------------------------------------------------------------------
    // Network (for WarTableOpenPacket)
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(partyId);
        buf.writeInt(pitIndex);
        buf.writeUtf(leaderName != null ? leaderName : "");
        buf.writeInt(targetChunkX);
        buf.writeInt(targetChunkZ);
        buf.writeUtf(targetType.name());
        buf.writeUtf(targetLabel != null ? targetLabel : "");
        buf.writeInt(originChunkX);
        buf.writeInt(originChunkZ);
        buf.writeLong(departureTime);
        buf.writeLong(arrivalTime);
        buf.writeLong(returnTime);
        buf.writeLong(completionTime);
        buf.writeUtf(state.name());
        buf.writeInt(orcUUIDs.size());
    }

    public static RaidParty decode(FriendlyByteBuf buf) {
        UUID partyId     = buf.readUUID();
        int pitIndex     = buf.readInt();
        String leader    = buf.readUtf();
        int targetX      = buf.readInt();
        int targetZ      = buf.readInt();
        TargetType type;
        try { type = TargetType.valueOf(buf.readUtf()); }
        catch (Exception e) { buf.readUtf(); type = TargetType.FREE_TARGET; }
        String label     = buf.readUtf();
        int originX      = buf.readInt();
        int originZ      = buf.readInt();
        long departure   = buf.readLong();
        long arrival     = buf.readLong();
        long returnTime  = buf.readLong();
        long completion  = buf.readLong();
        String stateStr  = buf.readUtf();
        int orcCount     = buf.readInt();

        // Orcs not sent over network — client only needs count for display
        List<UUID> orcs = new ArrayList<>();
        for (int i = 0; i < orcCount; i++) orcs.add(UUID.randomUUID());

        RaidParty party = new RaidParty(
                partyId, pitIndex, orcs, leader, 0, 0, 0, 0,
                targetX, targetZ, type, label,
                originX, originZ,
                departure, arrival, returnTime, completion
        );
        try { party.state = RaidState.valueOf(stateStr); }
        catch (Exception e) { party.state = RaidState.OUTBOUND; }
        return party;
    }
}