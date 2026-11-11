package dev.joblessreincarnation.client.boss;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Everything one boss bar shows, as the server last sent it: the whole state each time, sent when a player first comes
 * into range or comes back, and again whenever something changes (a hit, a new bar, a line, the end). The server's
 * numbers are the only truth; the client only smooths how they look. Any boss can use it; Paul's sparring match is first.
 *
 * @param id          one per fight; a later view with the same id replaces this one
 * @param dimension   where the fight is; the bar only shows to players in that dimension
 * @param centre      the middle of the fighting ground, so the nearest fight comes first
 * @param participant whether the player receiving this is the one fighting (the server sets it per receiver)
 * @param name        the boss's name on the plate
 * @param title       one line under the name
 * @param emblem      an 11×11 GUI sprite shown in the round medallion at the bar's left end
 * @param phases      how many bars the boss has; one gem each
 * @param phase       the bar now being emptied, from 0
 * @param progress    what is left of that bar, 0 to 1
 * @param colours     RGB per bar, used for the fill and for that bar's gem
 * @param sealable    whether this boss has a phase where the challenger may not cast; only then is the seal drawn
 * @param sealed      whether casting is barred right now
 * @param stage       where the fight is: getting ready, fighting, between bars, or over
 * @param stageTicks  ticks left in the intro or the pause between bars; 0 otherwise
 * @param transition  counts the changes from one bar to the next; a rise plays the refill and unsealing once
 * @param line        what the boss is saying, or null
 * @param lineId      counts the boss's lines; the same id sent again keeps the line where it is instead of restarting it
 * @param lineTicks   how much longer the line stays
 * @param arenaRadius the fighting ground's radius in blocks around {@code centre}, drawn as a ring on the ground; 0 for none
 */
public record BossBarView(UUID id, ResourceKey<Level> dimension, Vec3 centre, boolean participant, Component name,
                          Component title, ResourceLocation emblem, int phases, int phase, float progress, int[] colours,
                          boolean sealable, boolean sealed, Stage stage, int stageTicks, int transition,
                          @Nullable Component line, int lineId, int lineTicks, int arenaRadius) {
    public enum Stage { INTRO, ACTIVE, INTERMISSION, RESULT }

    /** The colour of one bar, or a plain red when the server sent fewer colours than bars. */
    public int colour(int bar) {
        return 0xFF000000 | (bar >= 0 && bar < colours.length ? colours[bar] : 0xB8323A);
    }
}
