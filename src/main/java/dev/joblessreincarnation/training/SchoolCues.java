package dev.joblessreincarnation.training;

import dev.joblessreincarnation.magic.SpellBurstOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One visual packet per observer. Only the fighter who needs an input or eye cue overrides particle culling. */
public final class SchoolCues {
    public static void send(ServerLevel level, int kind, @Nullable LivingEntity subject, Vec3 at) {
        send(level, subject == null ? new SpellBurstOptions(kind) : new SpellBurstOptions(kind, subject.getId()), subject, at);
    }

    /** The same, for a cue that carries more than its kind and subject, such as a tier. */
    public static void send(ServerLevel level, SpellBurstOptions options, @Nullable LivingEntity subject, Vec3 at) {
        for (ServerPlayer viewer : level.players()) {
            level.sendParticles(viewer, options, viewer == subject, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    private SchoolCues() {}
}
