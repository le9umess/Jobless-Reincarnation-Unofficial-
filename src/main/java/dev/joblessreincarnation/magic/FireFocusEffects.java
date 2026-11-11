package dev.joblessreincarnation.magic;

import java.util.Optional;
import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Presentation emitted from the real server item-use path. Never resolves or pays for a spell. */
public final class FireFocusEffects {
    public static final int INTERVAL_TICKS = 3;
    public static final int LIFETIME_TICKS = 6;

    public static void onUseTick(ServerPlayer caster, int usedTicks) {
        if (usedTicks < 0 || !SpellCasting.validActive(caster) || caster.isShiftKeyDown()
                || SpellCatalog.active(caster) != SpellCatalog.FIRE_BALL || !FireMagic.isLearned(caster)) return;
        int chant = FireMagic.chantTicks(caster);
        // Vanilla decrements remaining use time after this callback. Match the ready state at the end of this tick.
        int completedTicks = usedTicks + 1;
        if (completedTicks != chant && usedTicks % INTERVAL_TICKS != 0) return;
        Optional<Vec3> focus = visibleFocus(caster);
        if (focus.isEmpty()) return;
        Vec3 point = focus.get();
        float progress = Math.clamp((float) completedTicks / Math.max(1, chant), 0, 1);
        AttackSpellVisuals.send(caster.serverLevel(), new FireFocusOptions(progress, caster.getId(), caster.getData(ModAttachments.CASTING).activeFormStage()), point.x, point.y, point.z,
                1, 0, 0, 0, 0);
        if (completedTicks >= chant && caster.getData(ModAttachments.CASTING).claimFireReadyCue()) caster.serverLevel().playSound(null, point.x, point.y, point.z,
                SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 0.18F, 1.35F);
    }

    /** Matches the existing water cue's hand-side focus; obstructed/submerged foci are omitted. */
    public static Optional<Vec3> visibleFocus(LivingEntity caster) {
        if (!(caster.level() instanceof ServerLevel level) || !caster.isAlive() || caster.isSpectator())
            return Optional.empty();
        double yaw = Math.toRadians(caster.getYRot());
        double side = caster.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        Vec3 eye = caster.getEyePosition();
        Vec3 focus = eye.add(caster.getLookAngle().scale(1.05))
                .add(-Math.cos(yaw) * 0.19 * side, -0.15, -Math.sin(yaw) * 0.19 * side);
        if (!finite(eye) || !finite(focus)) return Optional.empty();
        BlockPos low = BlockPos.containing(Math.min(eye.x, focus.x) - 1,
                Math.min(eye.y, focus.y) - 1, Math.min(eye.z, focus.z) - 1);
        BlockPos high = BlockPos.containing(Math.max(eye.x, focus.x) + 1,
                Math.max(eye.y, focus.y) + 1, Math.max(eye.z, focus.z) + 1);
        if (!level.hasChunksAt(low, high) || waterAt(level, eye) || waterAt(level, focus)) return Optional.empty();
        HitResult hit = level.clip(new ClipContext(eye, focus, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, caster));
        return hit.getType() == HitResult.Type.MISS ? Optional.of(focus) : Optional.empty();
    }
    private static boolean waterAt(ServerLevel level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        var water = level.getFluidState(pos);
        if (!water.is(FluidTags.WATER)) return false;
        Vec3 local = point.subtract(Vec3.atLowerCornerOf(pos));
        return water.getShape(level, pos).toAabbs().stream().anyMatch(box -> box.contains(local));
    }
    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    private FireFocusEffects() {}
}
