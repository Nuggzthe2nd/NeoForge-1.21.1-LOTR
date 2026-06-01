package net.nuggz.lotrmc.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the Nethercrown ritual altar structure.
 *
 * Required structure (soul sand is the center, Y=0 reference):
 *
 *   Four crying obsidian pillars at the corners of a 5x5 grid:
 *     Offsets from center: (±2, 0..2, ±2) — 3 blocks tall each
 *
 *   Nine wither skeleton skull blocks anywhere within a 4-block
 *   Chebyshev radius of the center, at any Y from -1 to +4.
 *
 *   Soul sand at the exact center (Y=0).
 */
public class AltarValidator {

    private static final int[][] PILLAR_CORNERS = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
    private static final int PILLAR_HEIGHT = 3;
    private static final int SKULL_COUNT_REQUIRED = 9;
    private static final int SKULL_SEARCH_RADIUS = 4;
    private static final int SKULL_SEARCH_Y_MIN = -1;
    private static final int SKULL_SEARCH_Y_MAX = 4;

    // -------------------------------------------------------------------------
    // Full diagnostic scan — call on right-click, prints everything to chat
    // -------------------------------------------------------------------------

    /**
     * Scans the altar and returns a DiagnosticResult containing:
     *   - One line per check (pass/fail) for chat display
     *   - Whether the overall structure is valid
     */
    public static DiagnosticResult diagnose(Level level, BlockPos center) {
        List<String> lines = new ArrayList<>();
        boolean allValid = true;

        // --- Soul sand ---
        boolean hasSoulSand = level.getBlockState(center).is(Blocks.SOUL_SAND);
        lines.add(hasSoulSand
                ? "§a✔ Soul sand at center"
                : "§c✘ No soul sand at center — right-click the soul sand block");
        if (!hasSoulSand) allValid = false;

        // --- Pillars ---
        // Check each corner independently so the player knows exactly which one is wrong
        String[] cornerNames = {"NW (-2,_,-2)", "NE (-2,_,+2)", "SW (+2,_,-2)", "SE (+2,_,+2)"};
        int pillarsComplete = 0;
        for (int i = 0; i < PILLAR_CORNERS.length; i++) {
            int[] corner = PILLAR_CORNERS[i];
            int blocksFound = 0;
            for (int dy = 0; dy < PILLAR_HEIGHT; dy++) {
                BlockPos pos = center.offset(corner[0], dy, corner[1]);
                if (level.getBlockState(pos).is(Blocks.CRYING_OBSIDIAN)) blocksFound++;
            }
            boolean pillarComplete = blocksFound == PILLAR_HEIGHT;
            if (pillarComplete) {
                pillarsComplete++;
                lines.add("§a✔ Pillar " + cornerNames[i] + " — " + blocksFound + "/3 crying obsidian");
            } else {
                lines.add("§c✘ Pillar " + cornerNames[i] + " — " + blocksFound + "/3 crying obsidian");
                allValid = false;
            }
        }

        // Summary line for pillars
        lines.add(1, (pillarsComplete == 4 ? "§a" : "§e") // insert after soul sand line
                + "  Pillars: " + pillarsComplete + "/4 complete");

        // --- Wither skulls ---
        int skullsFound = countWitherSkulls(level, center);
        boolean enoughSkulls = skullsFound >= SKULL_COUNT_REQUIRED;
        lines.add(enoughSkulls
                ? "§a✔ Wither skeleton skulls: " + skullsFound + "/" + SKULL_COUNT_REQUIRED
                : "§c✘ Wither skeleton skulls: " + skullsFound + "/" + SKULL_COUNT_REQUIRED
                  + " (within " + SKULL_SEARCH_RADIUS + " blocks, Y " + SKULL_SEARCH_Y_MIN
                  + " to +" + SKULL_SEARCH_Y_MAX + ")");
        if (!enoughSkulls) allValid = false;

        // --- Overall ---
        lines.add(allValid
                ? "§2§l► Altar is valid. Right-click to begin the ritual."
                : "§4§l► Altar is incomplete.");

        return new DiagnosticResult(allValid, lines);
    }

    // -------------------------------------------------------------------------
    // Quick validate — used by NethercrownItem after diagnose() already passed
    // -------------------------------------------------------------------------

    public static ValidationResult validate(Level level, BlockPos center) {
        DiagnosticResult diag = diagnose(level, center);
        return diag.valid
                ? ValidationResult.SUCCESS
                : ValidationResult.fail("Altar structure incomplete.");
    }

    // -------------------------------------------------------------------------
    // Integrity check (called every 20 ticks during ritual)
    // -------------------------------------------------------------------------

    public static boolean isIntact(Level level, BlockPos center) {
        if (!level.getBlockState(center).is(Blocks.SOUL_SAND)) return false;
        for (int[] corner : PILLAR_CORNERS) {
            for (int dy = 0; dy < PILLAR_HEIGHT; dy++) {
                if (!level.getBlockState(center.offset(corner[0], dy, corner[1]))
                        .is(Blocks.CRYING_OBSIDIAN)) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int countWitherSkulls(Level level, BlockPos center) {
        int count = 0;
        for (int dx = -SKULL_SEARCH_RADIUS; dx <= SKULL_SEARCH_RADIUS; dx++) {
            for (int dz = -SKULL_SEARCH_RADIUS; dz <= SKULL_SEARCH_RADIUS; dz++) {
                for (int dy = SKULL_SEARCH_Y_MIN; dy <= SKULL_SEARCH_Y_MAX; dy++) {
                    BlockState state = level.getBlockState(center.offset(dx, dy, dz));
                    if (state.is(Blocks.WITHER_SKELETON_SKULL)
                            || state.is(Blocks.WITHER_SKELETON_WALL_SKULL)) count++;
                }
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    public static class DiagnosticResult {
        public final boolean valid;
        public final List<String> lines; // ready to send as chat messages

        public DiagnosticResult(boolean valid, List<String> lines) {
            this.valid = valid;
            this.lines = lines;
        }
    }

    public static class ValidationResult {
        public static final ValidationResult SUCCESS = new ValidationResult(true, null);
        public final boolean valid;
        public final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}