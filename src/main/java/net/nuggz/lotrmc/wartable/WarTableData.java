package net.nuggz.lotrmc.wartable;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A serializable snapshot of all mudpit data for the War Table UI.
 * Built on the server and sent to the client via WarTableOpenPacket.
 *
 * Immutable once constructed — the UI reads from this snapshot.
 */
public class WarTableData {

    public final List<PitEntry> pits;

    public WarTableData(List<PitEntry> pits) {
        this.pits = pits;
    }

    // -------------------------------------------------------------------------
    // Pit entry
    // -------------------------------------------------------------------------

    public static class PitEntry {
        public final int pitIndex;           // sequential index for display
        public final int capacity;
        public final int biomass;
        public final boolean isGestating;
        public final int gestationPercent;
        public final boolean isRaiding;
        public final String defaultOrder;    // "Guarding" or "Patrolling"

        // Leader — null if pit has no leader
        public final String leaderName;      // null if no leader
        public final int statStrength;
        public final int statTactics;
        public final int statPresence;

        public final int maxPopulation;
        public final List<OrcEntry> orcs;

        public PitEntry(int pitIndex, int capacity, int biomass,
                        boolean isGestating, int gestationPercent,
                        boolean isRaiding, String defaultOrder,
                        String leaderName, int statStrength,
                        int statTactics, int statPresence,
                        int maxPopulation,
                        List<OrcEntry> orcs) {
            this.pitIndex         = pitIndex;
            this.capacity         = capacity;
            this.biomass          = biomass;
            this.isGestating      = isGestating;
            this.gestationPercent = gestationPercent;
            this.isRaiding        = isRaiding;
            this.defaultOrder     = defaultOrder;
            this.leaderName       = leaderName;
            this.statStrength     = statStrength;
            this.statTactics      = statTactics;
            this.statPresence     = statPresence;
            this.maxPopulation    = maxPopulation;
            this.orcs             = orcs;
        }

        public boolean hasLeader() { return leaderName != null; }
    }

    // -------------------------------------------------------------------------
    // Orc entry
    // -------------------------------------------------------------------------

    public static class OrcEntry {
        public final UUID uuid;
        public final String name;       // custom name or "Orc #N"
        public final int scarCount;
        public final boolean isLeader;

        public OrcEntry(UUID uuid, String name, int scarCount, boolean isLeader) {
            this.uuid      = uuid;
            this.name      = name;
            this.scarCount = scarCount;
            this.isLeader  = isLeader;
        }
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(pits.size());
        for (PitEntry pit : pits) {
            buf.writeInt(pit.pitIndex);
            buf.writeInt(pit.capacity);
            buf.writeInt(pit.biomass);
            buf.writeBoolean(pit.isGestating);
            buf.writeInt(pit.gestationPercent);
            buf.writeBoolean(pit.isRaiding);
            buf.writeUtf(pit.defaultOrder);

            boolean hasLeader = pit.hasLeader();
            buf.writeBoolean(hasLeader);
            if (hasLeader) {
                buf.writeUtf(pit.leaderName);
                buf.writeInt(pit.statStrength);
                buf.writeInt(pit.statTactics);
                buf.writeInt(pit.statPresence);
            }

            buf.writeInt(pit.maxPopulation);
            buf.writeInt(pit.orcs.size());
            for (OrcEntry orc : pit.orcs) {
                buf.writeUUID(orc.uuid);
                buf.writeUtf(orc.name);
                buf.writeInt(orc.scarCount);
                buf.writeBoolean(orc.isLeader);
            }
        }
    }

    public static WarTableData decode(FriendlyByteBuf buf) {
        int pitCount = buf.readInt();
        List<PitEntry> pits = new ArrayList<>();

        for (int i = 0; i < pitCount; i++) {
            int pitIndex         = buf.readInt();
            int capacity         = buf.readInt();
            int biomass          = buf.readInt();
            boolean isGestating  = buf.readBoolean();
            int gestationPercent = buf.readInt();
            boolean isRaiding    = buf.readBoolean();
            String defaultOrder  = buf.readUtf();

            String leaderName = null;
            int str = 0, tac = 0, pre = 0;
            if (buf.readBoolean()) {
                leaderName = buf.readUtf();
                str        = buf.readInt();
                tac        = buf.readInt();
                pre        = buf.readInt();
            }

            int maxPopulation = buf.readInt();
            int orcCount = buf.readInt();
            List<OrcEntry> orcs = new ArrayList<>();
            for (int j = 0; j < orcCount; j++) {
                orcs.add(new OrcEntry(
                        buf.readUUID(),
                        buf.readUtf(),
                        buf.readInt(),
                        buf.readBoolean()));
            }

            pits.add(new PitEntry(pitIndex, capacity, biomass,
                    isGestating, gestationPercent, isRaiding,
                    defaultOrder, leaderName, str, tac, pre, maxPopulation, orcs));
        }

        return new WarTableData(pits);
    }
}