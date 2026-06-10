package net.nuggz.lotrmc.entity;

import net.nuggz.lotrmc.mudpit.MudpitBlockEntity;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.UUID;

public class OrcEntity extends Monster implements GeoEntity {

    // -------------------------------------------------------------------------
    // GeckoLib
    // -------------------------------------------------------------------------

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final RawAnimation ANIM_IDLE   = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_WALK   = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_ATTACK = RawAnimation.begin().thenPlay("attack");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "movement", 4, state -> {
            if (state.isMoving()) return state.setAndContinue(ANIM_WALK);
            return state.setAndContinue(ANIM_IDLE);
        }));

        registrar.add(new AnimationController<>(this, "attack", 2, state -> PlayState.STOP)
                .triggerableAnim("attack_trigger", ANIM_ATTACK));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // -------------------------------------------------------------------------
    // Construction + attributes
    // -------------------------------------------------------------------------

    public OrcEntity(EntityType<? extends OrcEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 2.0);
    }

    // -------------------------------------------------------------------------
    // Pit affiliation
    // -------------------------------------------------------------------------

    @Nullable private BlockPos pitPos = null;

    public void setPitPos(BlockPos pos) { this.pitPos = pos; }

    @Nullable public BlockPos getPitPos() { return pitPos; }

    // -------------------------------------------------------------------------
    // Leader state
    // -------------------------------------------------------------------------

    private boolean isLeader = false;
    @Nullable private OrcLeaderData leaderData = null;

    public boolean isLeader() { return isLeader; }

    @Nullable public OrcLeaderData getLeaderData() { return leaderData; }

    public void applyBranding(String name) {
        this.isLeader   = true;
        this.leaderData = OrcLeaderData.roll(random);
        this.setCustomName(net.minecraft.network.chat.Component.literal(name));
        this.setCustomNameVisible(true);
    }

    public void clearLeaderState() {
        this.isLeader   = false;
        this.leaderData = null;
        this.setCustomName(null);
        this.setCustomNameVisible(false);
    }

    // -------------------------------------------------------------------------
    // Scar system
    // -------------------------------------------------------------------------

    private static final int MAX_SCARS          = 10;
    private static final double SCAR_HEALTH_BONUS = 2.0;
    private static final double SCAR_DAMAGE_BONUS = 0.3;

    private int scarCount = 0;

    public int getScarCount() { return scarCount; }

    public int addScars(int count) {
        int added = Math.min(count, MAX_SCARS - scarCount);
        if (added <= 0) return 0;
        for (int i = 0; i < added; i++) {
            applyScarModifiers();
            scarCount++;
        }
        return added;
    }

    /**
     * Directly restore a scar count from a snapshot without re-applying
     * attribute modifiers (they'll be applied fresh via addScars).
     * Called when an orc returns from a raid.
     */
    public void restoreScarCount(int count) {
        this.scarCount = 0; // reset so addScars applies modifiers correctly
        if (count > 0) addScars(count);
    }

    /**
     * Restore full leader state from a snapshot.
     * Used when a leader orc returns from a raid.
     */
    public void restoreLeaderState(String name, OrcLeaderData data) {
        this.isLeader   = true;
        this.leaderData = data;
        if (name != null) {
            this.setCustomName(net.minecraft.network.chat.Component.literal(name));
            this.setCustomNameVisible(true);
        }
    }

    private void applyScarModifiers() {
        getAttribute(Attributes.MAX_HEALTH).addPermanentModifier(
                new AttributeModifier(ResourceLocation.fromNamespaceAndPath("lotrmc", "scar_health_" + scarCount), SCAR_HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));

        getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(
                new AttributeModifier(ResourceLocation.fromNamespaceAndPath("lotrmc", "scar_damage_" + scarCount), SCAR_DAMAGE_BONUS, AttributeModifier.Operation.ADD_VALUE));

        this.setHealth(this.getMaxHealth());
    }

    // -------------------------------------------------------------------------
    // Death — notify pit to clear leader slot
    // -------------------------------------------------------------------------

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        super.die(source);
        if (pitPos != null && level() instanceof ServerLevel serverLevel) {
            if (serverLevel.getBlockEntity(pitPos) instanceof MudpitBlockEntity pit) {
                pit.untrackOrc(getUUID());
                if (isLeader) pit.clearLeader();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Despawn prevention
    // -------------------------------------------------------------------------

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        // Orcs with pit affiliation never despawn — only unaffiliated ones
        // (e.g. spawned via debug command) clean up naturally
        return pitPos == null;
    }

    // -------------------------------------------------------------------------
    // Attack animation
    // -------------------------------------------------------------------------

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean result = super.doHurtTarget(target);
        if (result) triggerAnim("attack", "attack_trigger");
        return result;
    }

    // -------------------------------------------------------------------------
    // AI goals
    // -------------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Player.class, true, this::shouldAttackPlayer));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                this, Villager.class, false));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                this, IronGolem.class, true));
    }

    // -------------------------------------------------------------------------
    // Faction checks
    // -------------------------------------------------------------------------

    private boolean shouldAttackPlayer(LivingEntity target) {
        if (!(target instanceof Player player)) return true;
        if (level() instanceof ServerLevel serverLevel) {
            MudlandsChunkData data = MudlandsChunkData.get(serverLevel);
            UUID playerId = player.getUUID();
            if (playerId.equals(data.getSauronUUID())) return false;
            if (data.isLieutenant(playerId)) return false;
        }
        return true;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (!super.canAttack(target)) return false;
        if (target instanceof Player player && level() instanceof ServerLevel serverLevel) {
            MudlandsChunkData data = MudlandsChunkData.get(serverLevel);
            UUID playerId = player.getUUID();
            if (playerId.equals(data.getSauronUUID())) return false;
            if (data.isLieutenant(playerId)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (pitPos != null) tag.putLong("PitPos", pitPos.asLong());
        tag.putBoolean("IsLeader", isLeader);
        tag.putInt("ScarCount", scarCount);
        if (leaderData != null) tag.put("LeaderData", leaderData.save());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("PitPos"))     pitPos    = BlockPos.of(tag.getLong("PitPos"));
        isLeader  = tag.getBoolean("IsLeader");
        scarCount = tag.getInt("ScarCount");
        if (tag.contains("LeaderData")) leaderData = OrcLeaderData.load(tag.getCompound("LeaderData"));
    }
}