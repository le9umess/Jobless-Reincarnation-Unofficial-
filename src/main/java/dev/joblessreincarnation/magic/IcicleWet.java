package dev.joblessreincarnation.magic;

import java.util.UUID;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Short-lived, server-only water contact. Never serialized or copied to another entity/world. */
public record IcicleWet(UUID caster, long until) {
    public static final IcicleWet DRY = new IcicleWet(null, 0);
    public static final int WET_TICKS = 100;
    public static void splash(Entity target, Entity caster) {
        if (!(caster instanceof ServerPlayer owner) || target.level() != owner.level() || target.isRemoved()) return;
        boolean allowed = target instanceof PracticeDummyEntity dummy
                ? owner.getUUID().equals(dummy.ownerId()) && dummy.hasSupport()
                : target instanceof LivingEntity living && IcicleFields.eligible(owner, living);
        if (allowed) target.setData(ModAttachments.ICICLE_WET, new IcicleWet(owner.getUUID(), target.level().getGameTime() + WET_TICKS));
    }
    public static boolean wet(Entity target) {
        return target.isInWaterRainOrBubble() || contact(target, null);
    }
    public static boolean contact(Entity target, UUID caster) {
        if (!target.hasData(ModAttachments.ICICLE_WET)) return false;
        var wet=target.getData(ModAttachments.ICICLE_WET);long now=target.level().getGameTime();
        return wet.caster!=null && (caster==null || caster.equals(wet.caster)) && now<wet.until && wet.until-now<=WET_TICKS;
    }
    public static void dry(Entity target) { if (target.hasData(ModAttachments.ICICLE_WET)) target.setData(ModAttachments.ICICLE_WET, DRY); }
}
