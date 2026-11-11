package dev.joblessreincarnation.training;

import dev.joblessreincarnation.registry.ModAttachments;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;

/** Public, transient combat feedback. No mana, training progress or attacker ID crosses the wire. */
public record AuraVisual(Phase phase, long startedAt) {
    public enum Phase { OFF, READY, GUARD, BREAK, GATHER }

    public static final int FLASH_TICKS = 8;
    public static final AuraVisual OFF = new AuraVisual(Phase.OFF, 0);
    public static final AuraVisual READY = new AuraVisual(Phase.READY, 0);
    public static final StreamCodec<FriendlyByteBuf, AuraVisual> STREAM_CODEC = StreamCodec.of(
            (buf, visual) -> { buf.writeEnum(visual.phase); buf.writeVarLong(visual.startedAt); },
            buf -> new AuraVisual(buf.readEnum(Phase.class), buf.readVarLong()));

    public static AuraVisual of(Player player) {
        return player.hasData(ModAttachments.BATTLE_AURA_VISUAL) ? player.getData(ModAttachments.BATTLE_AURA_VISUAL) : OFF;
    }

    /** Zero outside a live impact. A tracker joining late sees only the remaining part of the flash. */
    public float flash(long gameTime, float partialTick) {
        if (phase != Phase.GUARD && phase != Phase.BREAK) return 0;
        float age = (gameTime - startedAt) + partialTick;
        return age < 0 || age >= FLASH_TICKS ? 0 : 1.0F - age / FLASH_TICKS;
    }
}
