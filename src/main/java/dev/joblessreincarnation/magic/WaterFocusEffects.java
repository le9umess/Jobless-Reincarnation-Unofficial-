package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;

/** Uses the same server-owned hand position and identity as the other two attack spells. */
public final class WaterFocusEffects {
    public static void onUseTick(ServerPlayer caster, int usedTicks) {
        if (usedTicks < 0 || !SpellCasting.validActive(caster) || caster.isShiftKeyDown()
                || SpellCatalog.active(caster) != SpellCatalog.WATER_BOLT) return;
        int chant = WaterMagic.chantTicks(caster);
        int completed = usedTicks + 1;
        if (completed != chant && usedTicks % 2 != 0) return;
        var focus = FireFocusEffects.visibleFocus(caster);
        if (focus.isEmpty()) return;
        var p = focus.get();
        var look = caster.getLookAngle();
        // 0..1 is the chant, 1..2 is the existing water shaping charge after it.
        float shaping = completed < chant ? (float) completed / chant
                : 1 + WaterControl.charge(caster, completed);
        AttackSpellVisuals.send(caster.serverLevel(), new SpellBurstOptions(SpellBurstOptions.WATER_FOCUS, caster.getId(),
                caster.getData(ModAttachments.CASTING).activeFormStage()), p.x, p.y, p.z,
                0, look.x, look.y, look.z, shaping);
    }
    private WaterFocusEffects() {}
}
