package net.nuggz.lotrmc.warmap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Simulates raid outcomes when a party arrives at its target.
 *
 * Called from RaidManager when a party's state transitions to AT_TARGET.
 * Results are stored temporarily until the party returns home.
 *
 * Simulation factors:
 *   - Party size (more orcs = more damage dealt, more casualties)
 *   - Leader strength (increases damage dealt → more loot, fewer enemy survivors)
 *   - Leader tactics (increases survival chance, scar gain rate)
 *   - Leader presence (reduces morale loss = fewer routs)
 *   - Total party scars (veterans fight better)
 *   - Target type and difficulty
 *
 * Output:
 *   - List of surviving orc UUIDs
 *   - Casualties (dead orcs)
 *   - Scars gained per surviving orc
 *   - Loot table result
 *   - Narrative description for the result screen
 */
public class RaidSimulator {

    // -------------------------------------------------------------------------
    // Main simulation entry point
    // -------------------------------------------------------------------------

    public static RaidManager.StoredResult simulate(ServerLevel level, RaidParty party) {
        Random rng = new Random(party.partyId.getMostSignificantBits()
                ^ level.getGameTime());

        // Calculate party power
        float partyPower = calculatePartyPower(party);

        // Calculate target difficulty
        float targetDifficulty = calculateTargetDifficulty(party, level, rng);

        // Power ratio — >1.0 means we outmatch the target
        float ratio = partyPower / Math.max(targetDifficulty, 0.1f);

        // Determine outcomes
        List<UUID> survivors   = new ArrayList<>();
        List<UUID> casualties  = new ArrayList<>();
        Map<UUID, Integer> scarGains = new HashMap<>();

        for (UUID orcUUID : party.orcUUIDs) {
            float survivalChance = calculateSurvivalChance(ratio, party, rng);
            if (rng.nextFloat() < survivalChance) {
                survivors.add(orcUUID);
                // Scars — injured survivors get scars based on how close they were to dying
                int scars = rollScars(ratio, party.leaderTactics, rng);
                if (scars > 0) scarGains.put(orcUUID, scars);
            } else {
                casualties.add(orcUUID);
            }
        }

        // Generate loot
        List<ItemStack> loot = generateLoot(party, ratio, rng);

        // Generate narrative
        String narrative = generateNarrative(party, survivors.size(),
                casualties.size(), ratio, loot);

        RaidResult result = new RaidResult(
                survivors, casualties, scarGains, loot, narrative, ratio);

        return new RaidManager.StoredResult(result);
    }

    // -------------------------------------------------------------------------
    // Power calculation
    // -------------------------------------------------------------------------

    private static float calculatePartyPower(RaidParty party) {
        float base = party.orcUUIDs.size() * 10.0f;

        // Leader strength bonus
        float strengthMult = 1.0f + (party.leaderStrength - 1) * 0.1f;

        // Veteran bonus from scars
        float scarBonus = 1.0f + (party.partyScarTotal * 0.05f);

        // Presence bonus (morale)
        float presenceBonus = 1.0f + (party.leaderPresence - 1) * 0.03f;

        return base * strengthMult * scarBonus * presenceBonus;
    }

    private static float calculateTargetDifficulty(RaidParty party, ServerLevel level, Random rng) {
        return switch (party.targetType) {
            case FREE_TARGET -> 15.0f + rng.nextFloat() * 10; // wilderness, low risk
            case POI -> {
                // Difficulty based on POI label
                String label = party.targetLabel != null
                        ? party.targetLabel.toLowerCase() : "";
                if (label.contains("village"))    yield 30.0f;
                if (label.contains("mansion"))    yield 60.0f;
                if (label.contains("stronghold")) yield 80.0f;
                if (label.contains("monument"))   yield 70.0f;
                if (label.contains("outpost"))    yield 40.0f;
                if (label.contains("player"))     yield 50.0f; // player base
                yield 35.0f; // unknown POI — moderate difficulty
            }
        };
    }

    // -------------------------------------------------------------------------
    // Survival and scar calculation
    // -------------------------------------------------------------------------

    private static float calculateSurvivalChance(float powerRatio,
                                                 RaidParty party, Random rng) {
        // Base survival from power ratio
        float base = 0.4f + (powerRatio * 0.3f);

        // Tactics bonus
        float tacticsBonus = party.leaderTactics * 0.03f;

        return Math.min(0.95f, base + tacticsBonus);
    }

    private static int rollScars(float powerRatio, int leaderTactics, Random rng) {
        // Only injured survivors get scars (those who barely made it)
        // Low power ratio = more dangerous = more scars
        float scarChance = 0.3f + ((1.0f - Math.min(powerRatio, 1.0f)) * 0.5f);
        if (rng.nextFloat() > scarChance) return 0;

        // Base 1 scar, tactics adds bonus rolls
        int scars = 1;
        int bonusRolls = leaderTactics / 4; // 0 at tac 1-3, 1 at 4-7, 2 at 8-10
        for (int i = 0; i < bonusRolls; i++) {
            if (rng.nextFloat() < 0.5f) scars++;
        }
        return scars;
    }

    // -------------------------------------------------------------------------
    // Loot generation
    // -------------------------------------------------------------------------

    private static List<ItemStack> generateLoot(RaidParty party,
                                                float powerRatio, Random rng) {
        List<ItemStack> loot = new ArrayList<>();
        if (party.targetType == RaidParty.TargetType.FREE_TARGET) {
            // Wilderness pillage — basic resources
            loot.add(new ItemStack(Items.OAK_LOG,
                    5 + rng.nextInt(10)));
            if (rng.nextFloat() < 0.3f)
                loot.add(new ItemStack(Items.IRON_INGOT, 1 + rng.nextInt(3)));
            return loot;
        }

        // POI loot — scales with power ratio and target type
        String label = party.targetLabel != null
                ? party.targetLabel.toLowerCase() : "";
        float successFactor = Math.min(powerRatio, 2.0f) / 2.0f; // 0-1

        if (label.contains("village")) {
            loot.add(new ItemStack(Items.WHEAT,
                    (int)(5 + successFactor * 15) + rng.nextInt(5)));
            loot.add(new ItemStack(Items.EMERALD,
                    (int)(1 + successFactor * 4) + rng.nextInt(2)));
            if (rng.nextFloat() < successFactor * 0.5f)
                loot.add(new ItemStack(Items.IRON_SWORD, 1));
            if (rng.nextFloat() < successFactor * 0.3f)
                loot.add(new ItemStack(Items.IRON_CHESTPLATE, 1));
        } else if (label.contains("stronghold")) {
            loot.add(new ItemStack(Items.ENDER_PEARL,
                    (int)(2 + successFactor * 8)));
            if (rng.nextFloat() < successFactor * 0.4f)
                loot.add(new ItemStack(Items.DIAMOND, 1 + rng.nextInt(3)));
        } else if (label.contains("mansion")) {
            loot.add(new ItemStack(Items.GOLDEN_APPLE,
                    (int)(1 + successFactor * 3)));
            if (rng.nextFloat() < successFactor * 0.5f)
                loot.add(new ItemStack(Items.DIAMOND_SWORD, 1));
        } else {
            // Generic POI loot
            loot.add(new ItemStack(Items.IRON_INGOT,
                    (int)(2 + successFactor * 8) + rng.nextInt(3)));
            if (rng.nextFloat() < 0.3f)
                loot.add(new ItemStack(Items.GOLD_INGOT, 1 + rng.nextInt(3)));
        }

        return loot;
    }

    // -------------------------------------------------------------------------
    // Narrative generation
    // -------------------------------------------------------------------------

    private static String generateNarrative(RaidParty party, int survivors,
                                            int casualties, float ratio,
                                            List<ItemStack> loot) {
        StringBuilder sb = new StringBuilder();
        String leaderRef = party.leaderName != null && !party.leaderName.isEmpty()
                ? party.leaderName : "Your warband";

        if (ratio >= 1.5f) {
            sb.append(leaderRef).append(" crushed the ")
                    .append(party.targetLabel != null ? party.targetLabel : "target")
                    .append(" with overwhelming force.");
        } else if (ratio >= 0.8f) {
            sb.append(leaderRef).append(" raided the ")
                    .append(party.targetLabel != null ? party.targetLabel : "target")
                    .append(" — a hard-fought victory.");
        } else {
            sb.append(leaderRef).append(" struggled against the ")
                    .append(party.targetLabel != null ? party.targetLabel : "target")
                    .append(". The battle was costly.");
        }

        if (casualties > 0) {
            sb.append(" ").append(casualties).append(" orc")
                    .append(casualties > 1 ? "s" : "").append(" fell.");
        }
        if (survivors > 0) {
            sb.append(" ").append(survivors).append(" returned.");
        } else {
            sb.append(" None survived.");
        }

        if (!loot.isEmpty()) {
            sb.append(" Spoils: ");
            for (int i = 0; i < loot.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(loot.get(i).getCount()).append("x ")
                        .append(loot.get(i).getHoverName().getString());
            }
            sb.append(".");
        }

        return sb.toString();
    }



    // -------------------------------------------------------------------------
    // RaidResult
    // -------------------------------------------------------------------------

    public static class RaidResult {
        public static final RaidResult EMPTY = new RaidResult(
                new ArrayList<>(), new ArrayList<>(),
                new HashMap<>(), new ArrayList<>(),
                "The raid produced no result.", 0.0f);

        public final List<UUID> survivorUUIDs;
        public final List<UUID> casualtyUUIDs;
        public final Map<UUID, Integer> scarsPerOrc;
        public final List<ItemStack> loot;
        public final String narrative;
        public final float powerRatio;

        public RaidResult(List<UUID> survivors, List<UUID> casualties,
                          Map<UUID, Integer> scarsPerOrc, List<ItemStack> loot,
                          String narrative, float powerRatio) {
            this.survivorUUIDs = survivors;
            this.casualtyUUIDs = casualties;
            this.scarsPerOrc   = scarsPerOrc;
            this.loot          = loot;
            this.narrative     = narrative;
            this.powerRatio    = powerRatio;
        }
    }
}