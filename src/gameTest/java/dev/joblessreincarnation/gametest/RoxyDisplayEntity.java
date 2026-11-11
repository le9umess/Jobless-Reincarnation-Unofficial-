package dev.joblessreincarnation.gametest;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Development-only stand-in that shows the Roxy candidate model through GeckoLib. It has no AI, cannot be
 * hurt, is never saved, and plays whichever clip the preview fixture selects.
 */
public final class RoxyDisplayEntity extends PathfinderMob implements GeoEntity {
    /** Clip names as they appear in the candidate's animation file, in preview order. */
    public static final String[] CLIPS = { "idle", "walk", "cast", "sit", "lecture" };
    private static final EntityDataAccessor<Integer> CLIP = SynchedEntityData.defineId(RoxyDisplayEntity.class, EntityDataSerializers.INT);
    private static final RawAnimation[] ANIMATIONS = new RawAnimation[CLIPS.length];
    static {
        for (int index = 0; index < CLIPS.length; index++) ANIMATIONS[index] = RawAnimation.begin().thenLoop("animation.roxy_tutor." + CLIPS[index]);
    }
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public RoxyDisplayEntity(EntityType<? extends RoxyDisplayEntity> type, Level level) {
        super(type, level);
        setNoAi(true); setInvulnerable(true); setSilent(true);
    }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.2);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CLIP, 0);
    }
    public void setClip(int clip) { entityData.set(CLIP, Math.floorMod(clip, CLIPS.length)); }
    public int clip() { return entityData.get(CLIP); }

    // A preview prop: never written to the world save and never despawned while the fixture runs.
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "display", 4, state -> state.setAndContinue(ANIMATIONS[clip()])));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
