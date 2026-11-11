package dev.joblessreincarnation.client.aura;

import dev.joblessreincarnation.magic.SpellSpriteOptions;
import dev.joblessreincarnation.training.AuraVisual;
import dev.joblessreincarnation.training.LightBladeTiming;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The sparks and sounds of a blow the aura took, and of the aura going out. Plays once per signal, for every player in view.
 * While gathering for Light Blade, each tier reached flashes once and chimes a step higher, so nobody has to count ticks.
 */
public final class AuraCues {
    private static final Map<Player, AuraVisual> SEEN = new WeakHashMap<>();
    private static final Map<Player, long[]> TIERS = new WeakHashMap<>();
    private static final int TIER_FLASH_TICKS = 6;

    /** 0 to 3, by the server's start tick and the shared thresholds; 0 when not gathering. */
    public static int gatherTier(Player player) {
        AuraVisual cue = AuraVisual.of(player);
        return cue.phase() == AuraVisual.Phase.GATHER ? LightBladeTiming.tierAt(player.level().getGameTime() - cue.startedAt()) : 0;
    }

    /** 1 just as a new tier is reached, fading to 0 over a few ticks. */
    public static float tierFlash(Player player, float partialTick) {
        long[] seen = TIERS.get(player);
        if (seen == null || seen[0] == 0 || AuraVisual.of(player).phase() != AuraVisual.Phase.GATHER) return 0.0F;
        float age = player.level().getGameTime() - seen[1] + partialTick;
        return age < 0 || age >= TIER_FLASH_TICKS ? 0.0F : 1.0F - age / TIER_FLASH_TICKS;
    }

    public static void afterTick(Minecraft minecraft) {
        MoveSwing.afterTick(minecraft);
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) return;
        for (Player player : level.players()) {
            AuraVisual cue = AuraVisual.of(player);
            int tier = gatherTier(player);
            long[] seen = TIERS.computeIfAbsent(player, p -> new long[2]);
            if (tier > seen[0]) tierReached(level, player, tier, player == minecraft.player);
            if (tier != seen[0]) { seen[0] = tier; seen[1] = level.getGameTime(); }
            AuraVisual last = SEEN.put(player, cue);
            // A player coming into view mid-flash has already had this one.
            if (last == null || cue.equals(last) || cue.flash(level.getGameTime(), 0.0F) <= 0.0F) continue;
            if (cue.phase() == AuraVisual.Phase.GUARD) guard(level, player);
            else if (cue.phase() == AuraVisual.Phase.BREAK) breakOut(level, player);
        }
    }

    /** A small flare above the raised sword and a few rising sparks, more with each tier: the glow alone is lost against a bright sky. */
    private static void tierReached(ClientLevel level, Player player, int tier, boolean self) {
        float pitch = 1.0F + 0.25F * (tier - 1);
        level.playLocalSound(player.getX(), player.getY() + 1.0, player.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, self ? 0.7F : 0.35F, pitch, false);
        RandomSource random = level.getRandom();
        Vec3 tip = player.position().add(0.0, player.getBbHeight() + 0.55, 0.0);
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 0.3F + 0.12F * tier, 3), true, tip.x, tip.y, tip.z, 0.0, 0.0, 0.0);
        for (int i = 0; i < 3 + 2 * tier; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.05F + random.nextFloat() * 0.04F, 8 + random.nextInt(6)), true,
                    tip.x + Math.cos(angle) * 0.15, tip.y - random.nextDouble() * 0.6, tip.z + Math.sin(angle) * 0.15,
                    Math.cos(angle) * 0.03, 0.06 + random.nextDouble() * 0.06, Math.sin(angle) * 0.03);
        }
    }

    private static void guard(ClientLevel level, Player player) {
        RandomSource random = level.getRandom();
        Vec3 chest = player.position().add(0.0, player.getBbHeight() * 0.62, 0.0);
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = 0.12 + random.nextDouble() * 0.2;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.05F + random.nextFloat() * 0.05F, 7 + random.nextInt(8)), true,
                    chest.x + Math.cos(angle) * 0.3, chest.y + (random.nextDouble() - 0.5) * 0.7, chest.z + Math.sin(angle) * 0.3,
                    Math.cos(angle) * speed, 0.06 + random.nextDouble() * 0.12, Math.sin(angle) * speed);
        }
        level.playLocalSound(player.getX(), player.getY() + 1.0, player.getZ(), SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 0.28F, 1.75F + random.nextFloat() * 0.25F, false);
    }

    private static void breakOut(ClientLevel level, Player player) {
        RandomSource random = level.getRandom();
        Vec3 chest = player.position().add(0.0, player.getBbHeight() * 0.55, 0.0);
        level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.FLASH, 1.3F, 4), true, chest.x, chest.y, chest.z, 0.0, 0.0, 0.0);
        for (int i = 0; i < 18; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = 0.16 + random.nextDouble() * 0.2;
            level.addParticle(new SpellSpriteOptions(SpellSpriteOptions.EMBER, 0.06F + random.nextFloat() * 0.07F, 10 + random.nextInt(10)), true,
                    chest.x + Math.cos(angle) * 0.35, chest.y + (random.nextDouble() - 0.5) * 1.1, chest.z + Math.sin(angle) * 0.35,
                    Math.cos(angle) * speed, 0.04 + random.nextDouble() * 0.16, Math.sin(angle) * speed);
        }
        level.playLocalSound(player.getX(), player.getY() + 1.0, player.getZ(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.9F, 0.6F, false);
        level.playLocalSound(player.getX(), player.getY() + 1.0, player.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.45F, 0.7F, false);
    }

    private AuraCues() {}
}
