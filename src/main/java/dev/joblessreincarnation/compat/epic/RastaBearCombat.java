package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.encounter.RastaBearEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationBuilder;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.HitAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.gameasset.ColliderPreset;
import yesman.epicfight.registry.entries.EpicFightAttributes;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.damagesource.StunType;

/** EF owns actual contact, guard and stun. The quadruped gameplay rig is independent of the visible Gecko model. */
public final class RastaBearCombat {
    public static AnimationAccessor<AttackAnimation> PAW;
    private static AnimationAccessor<HitAnimation> FLINCH;

    public static void install(IEventBus bus) {
        EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(event ->
                event.registerEntityPatch(ModContent.RASTA_BEAR.get(), Patch::new));
        bus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() ->
                Armatures.registerEntityTypeArmature(ModContent.RASTA_BEAR.get(), Armatures.RAVAGER)));
        bus.addListener((EntityAttributeCreationEvent event) -> event.put(ModContent.RASTA_BEAR.get(),
                RastaBearEntity.attributes().add(EpicFightAttributes.WEIGHT, 65).add(EpicFightAttributes.ARMOR_NEGATION)
                    .add(EpicFightAttributes.IMPACT).add(EpicFightAttributes.MAX_STRIKES, 1).add(EpicFightAttributes.STUN_ARMOR).build()));
    }

    public static void animations(AnimationBuilder builder) {
        PAW = builder.nextAccessor("encounter/bear_paw", Paw::new);
        FLINCH = builder.nextAccessor("encounter/bear_flinch", key -> new HitAnimation(0, key, Armatures.RAVAGER));
    }

    public static @Nullable Patch patch(RastaBearEntity bear) {
        return EpicFightCapabilities.getEntityPatch(bear, Patch.class);
    }

    public static final class Patch extends MobPatch<RastaBearEntity> {
        public Patch(RastaBearEntity bear) { super(bear, Factions.NEUTRAL); }
        @Override protected void initAI() { /* The encounter goal owns perception, commitment and disengagement. */ }
        @Override public void updateMotion(boolean considerInaction) { commonMobUpdateMotion(considerInaction); }
        @Override protected void initAnimator(Animator animator) {
            super.initAnimator(animator);
            animator.addLivingAnimation(LivingMotions.IDLE, Animations.RAVAGER_IDLE);
            animator.addLivingAnimation(LivingMotions.WALK, Animations.RAVAGER_WALK);
            animator.addLivingAnimation(LivingMotions.DEATH, Animations.RAVAGER_DEATH);
        }
        @Override public AssetAccessor<? extends StaticAnimation> getHitAnimation(StunType type) { return FLINCH; }
        @Override public boolean applyStun(StunType type, float duration) {
            if (type == StunType.NONE || !super.applyStun(type, duration)) return false;
            if (!getOriginal().level().isClientSide) getOriginal().recoil();
            return true;
        }
    }

    private static final class Paw extends AttackAnimation {
        Paw(AnimationAccessor<AttackAnimation> key) {
            super(0, key, Armatures.RAVAGER, new PawContact());
        }
        @Override public void tick(LivingEntityPatch<?> patch) {
            if (patch.getOriginal() instanceof RastaBearEntity bear && !bear.level().isClientSide) {
                var clock = patch.getAnimator().getPlayerFor(getAccessor());
                if (clock != null) {
                    float frame = clock.getElapsedTime() * 20;
                    if (frame + .0001F >= RastaBearEntity.SWIPE_TICK)
                        bear.advancePaw(frame + .0001F >= RastaBearEntity.CONTACT_TICK);
                }
            }
            super.tick(patch);
        }
        @Override public float getPlaySpeed(LivingEntityPatch<?> patch, DynamicAnimation animation) { return 1.0F; }
        @Override protected Vec3 getCoordVector(LivingEntityPatch<?> patch, AssetAccessor<? extends DynamicAnimation> animation) {
            return Vec3.ZERO;
        }
        @Override protected int getMaxStrikes(LivingEntityPatch<?> patch, Phase phase) { return 1; }
        @Override public void end(LivingEntityPatch<?> patch, AssetAccessor<? extends DynamicAnimation> next, boolean naturalEnd) {
            super.end(patch,next,naturalEnd);
            if (patch.getOriginal() instanceof RastaBearEntity bear && !bear.level().isClientSide) bear.finishPaw();
        }
    }

    private static final class PawContact extends AttackAnimation.Phase {
        PawContact() {
            super(0, 0, RastaBearEntity.CONTACT_TICK / 20F, .75F, RastaBearEntity.END_TICK / 20F, RastaBearEntity.END_TICK / 20F,
                    InteractionHand.MAIN_HAND, Armatures.RAVAGER.get().footRF, ColliderPreset.FIST);
        }
        @Override public List<Entity> getCollidingEntities(LivingEntityPatch<?> patch, AttackAnimation clip,
                float previous, float now, float speed) {
            if (!(patch.getOriginal() instanceof RastaBearEntity bear) || bear.level().isClientSide) return List.of();
            var victim = bear.takePawContact();
            return victim == null ? List.of() : List.of(victim);
        }
    }

    private RastaBearCombat() {}
}
