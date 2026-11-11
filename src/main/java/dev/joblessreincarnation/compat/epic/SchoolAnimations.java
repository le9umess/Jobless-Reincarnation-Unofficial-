package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.SchoolForm;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationBuilder;
import yesman.epicfight.api.animation.property.AnimationProperty.AttackAnimationProperty;
import yesman.epicfight.api.animation.types.ActionAnimation;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import dev.joblessreincarnation.training.LightBladeTiming;
import dev.joblessreincarnation.training.LightBladeTraining;
import yesman.epicfight.api.utils.math.ValueModifier;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.collider.OBBCollider;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.gameasset.ColliderPreset;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import yesman.epicfight.world.damagesource.StunType;

public final class SchoolAnimations {
    public static AnimationAccessor<AttackAnimation> ARM_STRIKE, FLOW_COUNTER;
    public static AnimationAccessor<ActionAnimation> TEAR_THROW;
    public static AnimationAccessor<StaticAnimation> LIGHT_GATHER, LIGHT_HOLD;
    public static AnimationAccessor<AttackAnimation> LIGHT_CUT;

    public static void register(AnimationBuilder builder) {
        ARM_STRIKE = builder.nextAccessor("v035/player/arm_strike", key -> new SchoolCut(key, SchoolForm.ARM_STRIKE,
                new ContactPhase(SchoolForm.ARM_STRIKE, .083F, .117F, .217F, .417F, .633F)));
        FLOW_COUNTER = builder.nextAccessor("v035/player/flow_counter", key -> new SchoolCut(key, SchoolForm.FLOW_COUNTER,
                new ContactPhase(SchoolForm.FLOW_COUNTER, .067F, .100F, .250F, .433F, .633F)));
        LIGHT_GATHER = builder.nextAccessor("v035/player/light_blade_gather", key -> new StaticAnimation(.05F, false, key, Armatures.BIPED));
        LIGHT_HOLD = builder.nextAccessor("v035/player/light_blade_hold", key -> new StaticAnimation(.05F, true, key, Armatures.BIPED));
        LIGHT_CUT = builder.nextAccessor("v035/player/light_blade_cut", key -> new SchoolCut(key, SchoolForm.LIGHT_BLADE,
                new ContactPhase(SchoolForm.LIGHT_BLADE, .017F, .033F, .200F, .467F, .767F)));
        TEAR_THROW = builder.nextAccessor("v035/player/tear_throw", key -> new ActionAnimation(.05F, key, Armatures.BIPED) {
            @Override protected Vec3 getCoordVector(LivingEntityPatch<?> patch, AssetAccessor<? extends DynamicAnimation> animation) {
                return Vec3.ZERO;
            }
        });
    }

    static boolean windingUp(Entity target) {
        var patch = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);
        if (patch == null || patch.getEntityState().getState(EntityState.PHASE_LEVEL) != 1) return false;
        var current = patch.getAnimator().getPlayerFor(null).getRealAnimation();
        return current != null && current.get() instanceof AttackAnimation;
    }

    /** Whether the target is winding up a later strike of one attack, so an earlier strike of it may already have landed. */
    public static boolean followUp(Entity target) {
        var patch = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);
        if (patch == null) return false;
        var playing = patch.getAnimator().getPlayerFor(null);
        if (playing == null || playing.getRealAnimation() == null || !(playing.getRealAnimation().get() instanceof AttackAnimation attack)
                || attack.phases.length < 2) return false;
        return attack.getPhaseByTime(playing.getElapsedTime()) != attack.phases[0];
    }

    private static final class SchoolCut extends AttackAnimation {
        private final SchoolForm form;
        SchoolCut(AnimationAccessor<AttackAnimation> key, SchoolForm form, Phase phase) {
            super(.05F, key, Armatures.BIPED, phase);
            // Authored startup/contact/recovery are real seconds, including on fast swords.
            addProperty(AttackAnimationProperty.ATTACK_SPEED_FACTOR, 0.0F);
            this.form = form;
        }

        @Override protected int getMaxStrikes(LivingEntityPatch<?> patch, Phase phase) { return 1; }

        @Override public EpicFightDamageSource getEpicFightDamageSource(LivingEntityPatch<?> patch, Entity target, Phase phase) {
            var damage = super.getEpicFightDamageSource(patch, target, phase);
            if (form == SchoolForm.LIGHT_BLADE && patch.getOriginal() instanceof ServerPlayer player) {
                int tier = player.getData(ModAttachments.SCHOOL_COMBAT).lightTier();
                damage.attachDamageModifier(ValueModifier.multiplier(LightBladeTiming.damageMultiplier(tier)));
            }
            boolean interrupts = form == SchoolForm.ARM_STRIKE && windingUp(target);
            // Remembered for the interruption cue: catching a later strike reads differently from stopping the first. Set on
            // every Arm Strike, so a strike whose damage never reaches the cue (Paul's is scored, not dealt) leaves nothing stale.
            if (form == SchoolForm.ARM_STRIKE && patch.getOriginal() instanceof ServerPlayer player && player.hasData(ModAttachments.SCHOOL_COMBAT))
                player.getData(ModAttachments.SCHOOL_COMBAT).armFollowUp(interrupts && followUp(target));
            return damage.setStunType(interrupts ? StunType.SHORT : StunType.NONE);
        }
    }

    private static final class ContactPhase extends AttackAnimation.Phase {
        private final SchoolForm form;
        private final int samples;
        private final OBBCollider blade;
        ContactPhase(SchoolForm form, float antic, float preDelay, float contact, float recovery, float end) {
            super(0.0F, antic, preDelay, contact, recovery, end, InteractionHand.MAIN_HAND, Armatures.BIPED.get().toolR, ColliderPreset.SWORD);
            this.form = form;
            CompoundTag preset = ColliderPreset.SWORD.serialize(new CompoundTag());
            samples = preset.getInt("number");
            preset.putInt("number", 1);
            blade = (OBBCollider) ColliderPreset.deserializeSimpleCollider(preset);
        }

        @Override public List<Entity> getCollidingEntities(LivingEntityPatch<?> patch, AttackAnimation clip, float before, float now, float speed) {
            if (!(patch.getOriginal() instanceof ServerPlayer player) || !player.hasData(ModAttachments.SCHOOL_COMBAT)) return List.of();
            var state = player.getData(ModAttachments.SCHOOL_COMBAT);
            boolean training = form == SchoolForm.LIGHT_BLADE && state.isPracticeCut();
            var dummy = training ? state.currentTrialDummy(player) : null;
            if (training && dummy == null) return List.of();
            // The Longsword of Light is judged where its dash is on the server's own clock (SwordReach), not where the
            // player's client has so far told the server it is: online that arrives a round trip late.
            Vec3 dash = form == SchoolForm.LIGHT_BLADE ? state.lightCutAt(now) : null;
            var around = dash == null ? player.getBoundingBox() : player.getBoundingBox().move(dash.subtract(player.position()));
            var candidates = training ? List.<LivingEntity>of() : player.level().getEntitiesOfClass(LivingEntity.class,
                    around.inflate(player.entityInteractionRange() + 1), target -> state.allowsContact(player, form, target));
            if (!training && candidates.isEmpty()) return List.of();

            // EF's pose matrices are floats. Subtract the world origin in double precision before
            // transforming the sword or testing a target; an absolute float loses half-blocks beyond 2^23.
            Vec3 origin = dash != null ? dash : player.position();
            int extra = clip.getProperty(AttackAnimationProperty.EXTRA_COLLIDERS).orElse(0);
            int count = Math.max(samples, Math.round((samples + extra) * speed));
            List<OBBCollider> sweep = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                float fraction = count == 1 ? 1.0F : (float)i / (count - 1);
                float time = Mth.lerp(fraction, before, now);
                var pose = clip.getPoseByTime(patch, time, 1.0F);
                var joint = patch.getArmature().getBoundTransformFor(pose, Armatures.BIPED.get().toolR);
                double remaining = 1.0 - fraction;
                // Model space is mirrored in x and z: the sample's offset d from origin enters as (-d.x, d.y, -d.z).
                Vec3 offset = dash != null ? state.lightCutAt(time).subtract(origin)
                        : new Vec3(patch.getXOld() - origin.x, patch.getYOld() - origin.y, patch.getZOld() - origin.z).scale(remaining);
                var translation = OpenMatrix4f.createTranslation((float) -offset.x, (float) offset.y, (float) -offset.z);
                var sample = blade.deepCopy();
                sample.transform(joint.mulFront(translation.mulBack(patch.getModelMatrix(fraction))));
                sweep.add(sample);
            }

            if (dummy != null) {
                var localBox = new OBBCollider(dummy.getBoundingBox().move(-origin.x, -origin.y, -origin.z));
                if (sweep.stream().anyMatch(sample -> sample.isCollide(localBox))) LightBladeTraining.completeCut(player, dummy);
                return List.of();
            }
            List<Entity> result = new ArrayList<>();
            for (LivingEntity target : candidates) {
                var localBox = new OBBCollider(target.getBoundingBox().move(-origin.x, -origin.y, -origin.z));
                if (sweep.stream().anyMatch(sample -> sample.isCollide(localBox))) result.add(target);
            }
            return result;
        }
    }

    private SchoolAnimations() {}
}
