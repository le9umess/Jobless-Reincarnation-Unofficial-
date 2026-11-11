package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** The stone gathering at the staff tip during the chant. Presentation from the real item-use path; it never resolves or pays for a spell. */
public final class StoneFocusEffects {
    public static final int INTERVAL_TICKS = 2;

    public static void onUseTick(ServerPlayer caster, int usedTicks) {
        if (usedTicks < 0 || !SpellCasting.validActive(caster) || caster.isShiftKeyDown()
                || SpellCatalog.active(caster) != SpellCatalog.STONE_CANNON || !StoneCannonSpell.isLearned(caster)) return;
        int chant = StoneCannonSpell.chantTicks(caster);
        // Vanilla decrements remaining use time after this callback. Match the ready state at the end of this tick.
        int completedTicks = usedTicks + 1;
        if (completedTicks != chant && usedTicks % INTERVAL_TICKS != 0) return;
        Optional<Vec3> focus = FireFocusEffects.visibleFocus(caster);
        if (focus.isEmpty()) return;
        Vec3 point = focus.get(), look = caster.getLookAngle();
        float progress = Math.clamp((float) completedTicks / Math.max(1, chant), 0.02F, 1);
        // Count 0 hands the client the offsets times the speed: where the staff points, and how far along the chant is.
        AttackSpellVisuals.send(caster.serverLevel(), new SpellBurstOptions(SpellBurstOptions.STONE_FOCUS, caster.getId(), caster.getData(ModAttachments.CASTING).activeFormStage()), point.x, point.y, point.z,
                0, look.x, look.y, look.z, progress);
        if (completedTicks >= chant && caster.getData(ModAttachments.CASTING).claimReadyCue(SpellCatalog.STONE_CANNON)) {
            caster.serverLevel().playSound(null, point.x, point.y, point.z, SoundEvents.DEEPSLATE_HIT, SoundSource.PLAYERS, 0.3F, 1.2F);
        }
    }

    private StoneFocusEffects() {}
}
