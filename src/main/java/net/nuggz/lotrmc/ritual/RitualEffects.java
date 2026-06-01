package net.nuggz.lotrmc.ritual;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.GameRules;

import java.util.List;

/**
 * All visual/audio/weather effects for the ritual.
 *
 * --- On ritual start (call once) ---
 *   playStartEffects()  — spiral particles, pillar lightning, darkness on player
 *
 * --- Each day's storm escalation (call once per day) ---
 *   applyDayStorm(day)  — day 0..4, gets progressively worse
 *
 * --- Called every 40 ticks while ritual is active ---
 *   tickAmbientEffects() — ongoing particle pulse around altar
 */
public class RitualEffects {

    // -------------------------------------------------------------------------
    // Ritual start — fires once when the nether star is used
    // -------------------------------------------------------------------------

    public static void playStartEffects(ServerLevel level, BlockPos center) {
        // Lightning at each pillar top (cosmetic — no damage)
        int[][] corners = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
        for (int[] corner : corners) {
            BlockPos pillarTop = center.offset(corner[0], 2, corner[1]);
            spawnCosmeticLightning(level, pillarTop);
        }

        // Soul fire spiral rising from center
        spawnSpiralParticles(level, center, 60);

        // Soul fire sound at altar
        level.playSound(null, center,
                SoundEvents.BEACON_ACTIVATE,
                SoundSource.AMBIENT, 2.0f, 0.5f);

        level.playSound(null, center,
                SoundEvents.WITHER_SPAWN,
                SoundSource.AMBIENT, 1.5f, 0.8f);

        // Apply Darkness effect to the ritualist
        level.getServer().getPlayerList().getPlayers().stream()
                .filter(p -> p.blockPosition().closerThan(center, 16))
                .forEach(p -> p.addEffect(
                        new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false)));
    }

    // -------------------------------------------------------------------------
    // Per-day storm escalation
    // -------------------------------------------------------------------------

    /**
     * @param ritualDay 0 = first day (day after ritual starts), 4 = final day
     */
    public static void applyDayStorm(ServerLevel level, BlockPos center, int ritualDay) {
        switch (ritualDay) {
            case 0 -> applyDay0(level, center); // light rain begins
            case 1 -> applyDay1(level, center); // heavy rain + thunder
            case 2 -> applyDay2(level, center); // permanent thunder, sky darkens
            case 3 -> applyDay3(level, center); // lightning strikes near players
            case 4 -> applyDay4(level, center); // climax — ritual completes
        }
    }

    /** Day 1 — light rain across the whole world */
    private static void applyDay0(ServerLevel level, BlockPos center) {
        level.setWeatherParameters(0, 6000, true, false); // rain, no thunder
        broadcastTitle(level, "§8A darkness stirs...", "§7Rain begins to fall across the land.");
        spawnSpiralParticles(level, center, 20);
    }

    /** Day 2 — heavy rain + thunder begins */
    private static void applyDay1(ServerLevel level, BlockPos center) {
        level.setWeatherParameters(0, 6000, true, true); // rain + thunder
        broadcastSubtitle(level, "§8The sky darkens.", "§7Thunder rolls across the horizon.");
        spawnSpiralParticles(level, center, 30);
        level.playSound(null, center, SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.AMBIENT, 4.0f, 0.8f);
    }

    /** Day 3 — permanent storm, sky color shift (extend weather so it won't stop) */
    private static void applyDay2(ServerLevel level, BlockPos center) {
        level.setWeatherParameters(0, 12000, true, true);
        broadcastSubtitle(level, "§4Something is being forged.", "§cThe storm will not cease.");

        // Apply Blindness briefly to all online players for dramatic effect
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
        }

        spawnSpiralParticles(level, center, 40);
        spawnCosmeticLightning(level, center);
    }

    /** Day 4 — random lightning strikes near each player across the world */
    private static void applyDay3(ServerLevel level, BlockPos center) {
        level.setWeatherParameters(0, 12000, true, true);
        broadcastSubtitle(level, "§4The ritual nears completion.", "§cLightning walks the earth.");

        // Strike lightning near (but not on) each player — dramatic, not lethal
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            BlockPos nearPlayer = player.blockPosition().offset(
                    level.random.nextIntBetweenInclusive(-8, 8),
                    0,
                    level.random.nextIntBetweenInclusive(-8, 8));
            spawnCosmeticLightning(level, nearPlayer);

            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 1, false, false));
        }

        spawnSpiralParticles(level, center, 60);
    }

    /** Day 5 — climax, crown forms, mudlands spawns */
    private static void applyDay4(ServerLevel level, BlockPos center) {
        // Storm stays — MudlandsManager will spawn the biome,
        // which feels like the storm "caused" the mudlands to erupt

        broadcastTitle(level, "§4§lIT HAS BEEN FORGED.", "§c§lA new darkness claims this world.");

        // Pillar lightning at all 4 corners
        int[][] corners = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
        for (int[] corner : corners) {
            spawnCosmeticLightning(level, center.offset(corner[0], 0, corner[1]));
        }

        // Big particle burst from center
        spawnSpiralParticles(level, center, 120);

        level.playSound(null, center, SoundEvents.WITHER_DEATH,
                SoundSource.AMBIENT, 4.0f, 0.5f);

        // Wither-style shockwave particle burst at altar
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5,
                1, 0, 0, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Ambient tick effects (called every ~40 ticks while ritual is active)
    // -------------------------------------------------------------------------

    public static void tickAmbientEffects(ServerLevel level, BlockPos center, int ritualDay) {
        // Intensity scales with ritual day
        int particleCount = 5 + (ritualDay * 4);
        double radius = 2.5;

        for (int i = 0; i < particleCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double r = level.random.nextDouble() * radius;
            double x = center.getX() + 0.5 + Math.cos(angle) * r;
            double z = center.getZ() + 0.5 + Math.sin(angle) * r;
            double y = center.getY() + 0.5 + level.random.nextDouble() * 2;

            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    x, y, z, 1, 0, 0.05, 0, 0.02);
        }

        // Rising soul particles from the center column
        level.sendParticles(ParticleTypes.SOUL,
                center.getX() + 0.5,
                center.getY() + 0.5,
                center.getZ() + 0.5,
                2, 0.2, 0.5, 0.2, 0.01);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void spawnSpiralParticles(ServerLevel level, BlockPos center, int count) {
        for (int i = 0; i < count; i++) {
            double angle = (i / (double) count) * Math.PI * 4; // two full rotations
            double radius = 1.5 + (i / (double) count) * 1.5;
            double x = center.getX() + 0.5 + Math.cos(angle) * radius;
            double z = center.getZ() + 0.5 + Math.sin(angle) * radius;
            double y = center.getY() + (i / (double) count) * 3.0;

            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    x, y, z, 1, 0, 0, 0, 0.02);
        }
    }

    private static void spawnCosmeticLightning(ServerLevel level, BlockPos pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        bolt.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        bolt.setVisualOnly(true); // cosmetic — no fire, no damage, no creeper conversion
        level.addFreshEntity(bolt);
    }

    private static void broadcastTitle(ServerLevel level, String title, String subtitle) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                    net.minecraft.network.chat.Component.literal(title)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                    net.minecraft.network.chat.Component.literal(subtitle)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                    10, 70, 20));
        }
    }

    // Line1 in subtitle font (smaller than title, center screen).
    // Line2 in action bar (above hotbar, smaller still).
    // Empty title is required for the subtitle slot to actually render.
    private static void broadcastSubtitle(ServerLevel level, String line1, String line2) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                    net.minecraft.network.chat.Component.empty()));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                    net.minecraft.network.chat.Component.literal(line1)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
                    10, 50, 20));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                    net.minecraft.network.chat.Component.literal(line2)));
        }
    }
}