package net.nuggz.lotrmc.entity;

import net.minecraft.core.BlockPos;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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

import java.util.UUID;

/**
 * Base orc entity. All orc variants (grunt, archer, berserker, etc.) extend this.
 *
 * Faction logic:
 *   - Never attacks Sauron (the designated player UUID in MudlandsChunkData)
 *   - Never attacks lieutenants (UUIDs in MudlandsChunkData.getLieutenants())
 *   - Attacks all other players and non-orc mobs
 *
 * Animation controllers are minimal stubs — swap in your real animation
 * names once you have your Blockbench animations ready.
 */
public class OrcEntity extends Monster implements GeoEntity {

    // -------------------------------------------------------------------------
    // GeckoLib
    // -------------------------------------------------------------------------

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Animation resource keys — must match the names in your .animation.json
    private static final RawAnimation ANIM_IDLE   = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_WALK   = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_ATTACK = RawAnimation.begin().thenPlay("attack");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "movement", 4, state -> {
            if (state.isMoving()) {
                return state.setAndContinue(ANIM_WALK);
            }
            return state.setAndContinue(ANIM_IDLE);
        }));

        registrar.add(new AnimationController<>(this, "attack", 2, state ->
                PlayState.STOP) // attack is triggered imperatively in performAttack()
        );
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // -------------------------------------------------------------------------
    // Construction + attributes
    // -------------------------------------------------------------------------

    public OrcEntity(EntityType<? extends OrcEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Base attribute set. Variants override this by calling
     * AttributeSupplier.Builder on top of these values.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.23)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 2.0);
    }

    // Field
    private BlockPos pitPos = null;

    // Setter/getter
    public void setPitPos(BlockPos pos) { this.pitPos = pos; }
    public BlockPos getPitPos() { return pitPos; }

    // Save/load in your NBT methods:
    // save: if (pitPos != null) tag.putLong("PitPos", pitPos.asLong());
    // load: if (tag.contains("PitPos")) pitPos = BlockPos.of(tag.getLong("PitPos"));

    // -------------------------------------------------------------------------
    // AI goals
    // -------------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // Movement
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        // Targeting — order matters; more specific checks go first
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

    /**
     * Returns true if this orc should attack the given player.
     * False for Sauron and all current lieutenants.
     */
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

    /**
     * Also called by the hurt-by retaliation goal — prevents orcs from
     * retaliating if a Sauron/lieutenant accidentally hits them.
     */
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
}