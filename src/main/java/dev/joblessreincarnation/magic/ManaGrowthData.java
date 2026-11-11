package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;

/** Server-owned persistent capacity progress. Payment authorization and uniqueness belong to its caller. */
public final class ManaGrowthData {
    // Decode as long before clamping so oversized integral NBT values cannot wrap through an int codec.
    public static final Codec<ManaGrowthData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("earned_bonus", 0L).forGetter(data -> (long) data.earnedBonus()),
            Codec.LONG.optionalFieldOf("cooldown_remaining", 0L).forGetter(data -> (long) data.cooldownRemaining()),
            Codec.LONG.optionalFieldOf("round_maximum", 0L).forGetter(data -> (long) data.roundMaximum()),
            Codec.LONG.optionalFieldOf("paid_in_round", 0L).forGetter(data -> (long) data.paidInRound()),
            Codec.STRING.optionalFieldOf("config_signature", "").forGetter(ManaGrowthData::configSignature)
    ).apply(instance, ManaGrowthData::new));

    private int earnedBonus;
    private int cooldownRemaining;
    private int roundMaximum;
    private int paidInRound;
    private String configSignature;
    private transient Object manaIdentity;

    public ManaGrowthData() {
        this(0L, 0L, 0L, 0L, "");
    }

    public ManaGrowthData(long earnedBonus, long cooldownRemaining, long roundMaximum,
            long paidInRound, String configSignature) {
        this.earnedBonus = (int) Math.clamp(earnedBonus, 0L, ManaGrowthRules.MAX_BONUS_LIMIT);
        this.cooldownRemaining = (int) Math.clamp(cooldownRemaining, 0L, ManaGrowthRules.MAX_COOLDOWN_TICKS);
        this.configSignature = configSignature == null || configSignature.length() > 128 ? "" : configSignature;
        if (roundMaximum >= 1L && roundMaximum <= ManaGrowthRules.MAX_EFFECTIVE_MAXIMUM
                && paidInRound > 0L && this.cooldownRemaining == 0 && !this.configSignature.isEmpty()) {
            this.roundMaximum = (int) roundMaximum;
            // Further accumulated spending cannot change any threshold once this bound is reached.
            this.paidInRound = (int) Math.min(paidInRound, ManaGrowthRules.MAX_EFFECTIVE_MAXIMUM);
        }
    }

    public int earnedBonus() { return earnedBonus; }
    public int cooldownRemaining() { return cooldownRemaining; }
    public int roundMaximum() { return roundMaximum; }
    public int paidInRound() { return paidInRound; }
    public String configSignature() { return configSignature; }

    /** Disabling future growth preserves the already earned, configuration-limited capacity. */
    public int effectiveMaximum(ManaGrowthRules rules) {
        Objects.requireNonNull(rules, "rules");
        return rules.baseMaximum() + Math.min(earnedBonus, rules.bonusLimit());
    }

    public void refreshRules(ManaGrowthRules rules) {
        Objects.requireNonNull(rules, "rules");
        String signature = rules.signature();
        if (!signature.equals(configSignature)) {
            cancelRound();
            configSignature = signature;
        }
        if (!rules.enabled() || earnedBonus >= rules.bonusLimit() || cooldownRemaining > 0
                || (roundMaximum != 0 && roundMaximum != effectiveMaximum(rules))) cancelRound();
    }

    /** The first runtime binding may follow a valid disk load; a replacement denotes an external reset. */
    public void bindMana(Object identity) {
        Objects.requireNonNull(identity, "identity");
        if (manaIdentity != null && manaIdentity != identity) cancelRound();
        manaIdentity = identity;
    }

    public void cancelRound() {
        roundMaximum = 0;
        paidInRound = 0;
    }

    /** Called once per eligible online game tick; this method never starts or advances a training round. */
    public void tick(ManaGrowthRules rules, boolean eligible) {
        refreshRules(rules);
        if (!eligible || !rules.enabled()) {
            cancelRound();
            return;
        }
        if (cooldownRemaining > 0) {
            cancelRound();
            cooldownRemaining--;
        }
    }

    /**
     * Accepts a single authorized, already committed payment and returns only the capacity actually earned.
     * The caller must consume its payment receipt once; replay protection is not inferred from these values.
     */
    public int recordPayment(ManaGrowthRules rules, int beforeMana, int afterMana, int actualPaid) {
        refreshRules(rules);
        if (!rules.enabled() || cooldownRemaining > 0 || earnedBonus >= rules.bonusLimit()) return 0;
        int maximum = effectiveMaximum(rules);
        if (actualPaid <= 0 || afterMana < 0 || beforeMana <= afterMana || beforeMana > maximum
                || (long) beforeMana - afterMana != actualPaid) return 0;

        if (roundMaximum == 0) {
            if (beforeMana < ManaGrowthRules.highThreshold(maximum)) return 0;
            roundMaximum = maximum;
        }
        paidInRound = (int) Math.min((long) paidInRound + actualPaid, ManaGrowthRules.MAX_EFFECTIVE_MAXIMUM);
        int low = ManaGrowthRules.lowThreshold(roundMaximum);
        if (paidInRound < ManaGrowthRules.requiredSpend(roundMaximum) || beforeMana <= low || afterMana > low) return 0;

        int gained = Math.min(rules.gainPerRound(), rules.bonusLimit() - earnedBonus);
        earnedBonus += gained;
        cancelRound();
        cooldownRemaining = rules.cooldownTicks();
        return gained;
    }
}
