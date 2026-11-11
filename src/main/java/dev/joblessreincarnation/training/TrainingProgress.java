package dev.joblessreincarnation.training;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Personal training milestones. Only server-side code changes this attachment. */
public final class TrainingProgress {
    public static final Codec<TrainingProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("target_hits", 0).forGetter(TrainingProgress::targetHits),
            Codec.BOOL.optionalFieldOf("charged_target_hit", false).forGetter(TrainingProgress::chargedTargetHit),
            Codec.BOOL.optionalFieldOf("claimed", false).forGetter(TrainingProgress::claimed),
            Codec.INT.optionalFieldOf("fire_target_hits", 0).forGetter(TrainingProgress::fireTargetHits)
    ).apply(instance, TrainingProgress::new));

    private int targetHits;
    private boolean chargedTargetHit;
    private boolean claimed;
    private int fireTargetHits;

    public TrainingProgress() { this(0, false, false); }

    /** Existing three-field callers still describe water training only. */
    public TrainingProgress(int targetHits, boolean chargedTargetHit, boolean claimed) {
        this(targetHits, chargedTargetHit, claimed, 0);
    }

    public TrainingProgress(int targetHits, boolean chargedTargetHit, boolean claimed, int fireTargetHits) {
        this.targetHits = Math.clamp(targetHits, 0, TrainingTrials.HIT_GOAL);
        this.chargedTargetHit = chargedTargetHit;
        this.claimed = claimed;
        this.fireTargetHits = Math.clamp(fireTargetHits, 0, FireTrainingTrials.HIT_GOAL);
    }

    public int targetHits() { return targetHits; }
    public boolean chargedTargetHit() { return chargedTargetHit; }
    public boolean claimed() { return claimed; }
    public int fireTargetHits() { return fireTargetHits; }

    public boolean recordTargetHit(boolean charged) {
        int before = targetHits;
        boolean chargedBefore = chargedTargetHit;
        targetHits = Math.min(TrainingTrials.HIT_GOAL, targetHits + 1);
        chargedTargetHit |= charged;
        return before != targetHits || chargedBefore != chargedTargetHit;
    }

    public boolean recordFireTargetHit() {
        if (fireTargetHits >= FireTrainingTrials.HIT_GOAL) return false;
        fireTargetHits++;
        return true;
    }

    public boolean claim() {
        if (claimed) return false;
        claimed = true;
        return true;
    }
}
