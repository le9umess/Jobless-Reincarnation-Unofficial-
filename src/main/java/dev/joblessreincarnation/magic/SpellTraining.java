package dev.joblessreincarnation.magic;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import dev.joblessreincarnation.registry.ModAttachments;

/** Gameplay practice stages, separate from the source material's magic ranks. */
public final class SpellTraining {
    public enum Stage {
        NOVICE("novice", 5), PRACTICED("practiced", 4), ADEPT("adept", 3);

        private final String key;
        private final int chantFifths;

        Stage(String key, int chantFifths) {
            this.key = key;
            this.chantFifths = chantFifths;
        }

        public Component title() { return Component.translatable("training.jobless_reincarnation." + key); }
    }

    /** Public appearance for one cast; private practice totals remain on the owner. */
    public static int formStage(LivingEntity caster, SpellCatalog spell) {
        if (caster.hasData(ModAttachments.CASTING)) {
            CastingData active = caster.getData(ModAttachments.CASTING);
            if (spell.id().equals(active.activeSpell())) return active.activeFormStage();
        }
        return stage(spell.practiceCount(caster)).ordinal();
    }

    public static int practicedAt() { return MagicConfig.PRACTICED_CASTS.getAsInt(); }
    public static int adeptAt() { return Math.max(practicedAt() + 1, MagicConfig.ADEPT_CASTS.getAsInt()); }

    public static Stage stage(int successfulCasts) {
        if (successfulCasts >= adeptAt()) return Stage.ADEPT;
        return successfulCasts >= practicedAt() ? Stage.PRACTICED : Stage.NOVICE;
    }

    public static int chantTicks(int baseTicks, int successfulCasts) {
        // Round up and retain at least one tick: practice never grants silent casting.
        return Math.max(1, (baseTicks * stage(successfulCasts).chantFifths + 4) / 5);
    }

    public static int nextMilestone(int successfulCasts) {
        return switch (stage(successfulCasts)) {
            case NOVICE -> practicedAt();
            case PRACTICED -> adeptAt();
            case ADEPT -> 0;
        };
    }

    public static Component progressText(int successfulCasts) {
        int next = nextMilestone(successfulCasts);
        return next == 0
                ? Component.translatable("hud.jobless_reincarnation.practice_complete", successfulCasts)
                : Component.translatable("hud.jobless_reincarnation.practice", successfulCasts, next);
    }

    private SpellTraining() {}
}
