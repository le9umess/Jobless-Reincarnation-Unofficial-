package dev.joblessreincarnation.training;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Personal sword lesson facts. Eligibility and hit timing belong to the server runtime. */
public final class SwordTrainingProgress {
    public static final int GOAL = 20;
    public static final Codec<SwordTrainingProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("started", false).forGetter(SwordTrainingProgress::started),
            Codec.INT.optionalFieldOf("hits", 0).forGetter(SwordTrainingProgress::hits),
            Codec.BOOL.optionalFieldOf("recognized", false).forGetter(SwordTrainingProgress::recognized),
            Codec.BOOL.optionalFieldOf("aura_drill", false).forGetter(SwordTrainingProgress::auraDrill),
            Codec.BOOL.optionalFieldOf("aura_awakened", false).forGetter(SwordTrainingProgress::auraAwakened),
            Codec.INT.optionalFieldOf("school_practices", 0).forGetter(p -> p.schoolPractices),
            Codec.INT.optionalFieldOf("school_forms", 0).forGetter(p -> p.masteredForms),
            Codec.BOOL.optionalFieldOf("first_school_note", false).forGetter(SwordTrainingProgress::firstNoteCopied),
            Codec.BOOL.optionalFieldOf("light_blade_insight", false).forGetter(SwordTrainingProgress::hasLightInsight)
    ).apply(instance, SwordTrainingProgress::new));

    public enum Phase { NOT_STARTED, PRACTICING, READY, DONE }

    private boolean started;
    private int hits;
    private boolean recognized;
    private boolean auraDrill;
    private boolean auraAwakened;
    private int schoolPractices;
    private int masteredForms;
    private boolean firstNoteCopied;
    private boolean lightInsight;

    public SwordTrainingProgress() { this(false, 0, false); }

    public SwordTrainingProgress(boolean started, int hits, boolean recognized) {
        this(started, hits, recognized, false, false, 0, 0, false, false);
    }

    private SwordTrainingProgress(boolean started, int hits, boolean recognized, boolean drill, boolean awakened, int practices, int learned, boolean copied, boolean insight) {
        this.started = started;
        this.hits = started ? Math.clamp(hits, 0, GOAL) : 0;
        this.recognized = started && this.hits == GOAL && recognized;
        this.auraDrill = this.recognized && drill;
        this.auraAwakened = this.auraDrill && awakened;
        this.schoolPractices = this.recognized ? Math.max(0, practices) & 7 : 0;
        this.masteredForms = Math.max(0, learned) & this.schoolPractices;
        this.firstNoteCopied = this.recognized && copied;
        this.lightInsight = this.auraAwakened && mastered(SchoolForm.ARM_STRIKE) && insight;
        if (this.lightInsight && (practices & SchoolForm.LIGHT_BLADE.practiceBit) != 0) {
            this.schoolPractices |= SchoolForm.LIGHT_BLADE.practiceBit;
            if ((learned & SchoolForm.LIGHT_BLADE.practiceBit) != 0) this.masteredForms |= SchoolForm.LIGHT_BLADE.practiceBit;
        }
    }

    public boolean started() { return started; }
    public int hits() { return hits; }
    public boolean recognized() { return recognized; }

    public boolean auraDrill() { return auraDrill; }
    public boolean auraAwakened() { return auraAwakened; }

    public boolean hasPractice(SchoolForm form) { return (schoolPractices & form.practiceBit) != 0; }

    public boolean firstNoteCopied() { return firstNoteCopied; }
    void copiedFirstNote() { firstNoteCopied = true; }

    public boolean mastered(SchoolForm form) { return (masteredForms & form.practiceBit) != 0; }

    public void completePractice(SchoolForm form) {
        if (hasPractice(form)) masteredForms |= form.practiceBit;
    }

    public boolean hasLightInsight() { return lightInsight; }

    boolean recordLightInsight() {
        if (lightInsight || !auraAwakened || !mastered(SchoolForm.ARM_STRIKE)) return false;
        lightInsight = true;
        return true;
    }

    public boolean openPractice(SchoolForm form) {
        if (!recognized || hasPractice(form) || (form == SchoolForm.LIGHT_BLADE && !lightInsight)) return false;
        schoolPractices |= form.practiceBit;
        return true;
    }

    public boolean completeAuraDrill() {
        if (!recognized || auraDrill) return false;
        auraDrill = true;
        return true;
    }

    public boolean awakenAura() {
        if (!auraDrill || auraAwakened) return false;
        auraAwakened = true;
        return true;
    }

    public Phase phase() {
        if (!started) return Phase.NOT_STARTED;
        if (recognized) return Phase.DONE;
        return hits == GOAL ? Phase.READY : Phase.PRACTICING;
    }

    public boolean start() {
        if (started) return false;
        started = true;
        return true;
    }

    public boolean recordHit() {
        if (!started || hits >= GOAL || recognized) return false;
        hits++;
        return true;
    }

    public boolean recognize() {
        if (phase() != Phase.READY) return false;
        recognized = true;
        return true;
    }
}
