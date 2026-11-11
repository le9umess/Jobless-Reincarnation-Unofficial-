package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Persistent per-player state. Only the logical server mutates this object. */
public final class ManaData {
    public static final Codec<ManaData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("mana").forGetter(ManaData::mana),
            Codec.INT.fieldOf("maximum").forGetter(ManaData::maximum),
            Codec.INT.optionalFieldOf("recovery_delay", 0).forGetter(ManaData::recoveryDelay),
            Codec.INT.optionalFieldOf("cooldown", 0).forGetter(ManaData::cooldown),
            Codec.BOOL.optionalFieldOf("overcast_locked", false).forGetter(ManaData::overcastLocked),
            Codec.DOUBLE.optionalFieldOf("regen_fraction", 0.0).forGetter(d -> d.regenFraction)
    ).apply(instance, ManaData::new));

    // The client only needs the gauge values. Timers remain on the server.
    public static final StreamCodec<ByteBuf, ManaData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ManaData::mana,
            ByteBufCodecs.VAR_INT, ManaData::maximum,
            ByteBufCodecs.BOOL, ManaData::overcastLocked,
            (mana, maximum, locked) -> new ManaData(mana, maximum, 0, 0, locked, 0));

    private int mana;
    private int maximum;
    private int recoveryDelay;
    private int cooldown;
    private boolean overcastLocked;
    private double regenFraction;
    private long paymentSequence;

    public ManaData(int mana, int maximum, int recoveryDelay, int cooldown) {
        this(mana, maximum, recoveryDelay, cooldown, false, 0);
    }

    private ManaData(int mana, int maximum, int recoveryDelay, int cooldown, boolean locked, double fraction) {
        overcastLocked = locked;
        regenFraction = fraction >= 0 && fraction < 1 ? fraction : 0;
        this.maximum = Math.max(1, maximum);
        this.mana = Math.clamp(mana, 0, this.maximum);
        this.recoveryDelay = Math.max(0, recoveryDelay);
        this.cooldown = Math.max(0, cooldown);
    }

    public static ManaData full(int maximum) {
        return new ManaData(maximum, maximum, 0, 0);
    }

    public int mana() { return mana; }
    public int maximum() { return maximum; }
    public int recoveryDelay() { return recoveryDelay; }
    public int cooldown() { return cooldown; }

    public boolean overcastLocked() { return overcastLocked; }

    public boolean canCast(int cost, boolean creative) { return canPay(cost, creative, SpellPayment.NORMAL); }

    boolean canPay(int cost, boolean creative, SpellPayment mode) {
        if (cost <= 0 || cooldown != 0) return false;
        if (mode == SpellPayment.OVERCAST) return !creative && !overcastLocked && mana < cost;
        return creative || mana >= cost;
    }

    public boolean trySpend(int cost, int delay, int cooldownTicks, boolean creative) {
        return trySpendWithReceipt(cost, delay, cooldownTicks, creative) != null;
    }

    /** A receipt proves payment only; callers award growth after the spell commits. */
    public Payment trySpendWithReceipt(int cost, int delay, int cooldownTicks, boolean creative) {
        return trySpendWithReceipt(cost, delay, cooldownTicks, creative, SpellPayment.NORMAL);
    }

    Payment trySpendWithReceipt(int cost, int delay, int cooldownTicks, boolean creative, SpellPayment mode) {
        if (!canPay(cost, creative, mode)) return null;
        int before = mana;
        boolean overcast = mode == SpellPayment.OVERCAST;
        if (overcast) {
            mana = 0;
            overcastLocked = true;
        } else if (!creative) {
            mana -= cost;
        }
        recoveryDelay = Math.max(0, delay);
        cooldown = Math.max(1, cooldownTicks);
        return new Payment(this, ++paymentSequence, before, mana, maximum, overcast);
    }

    /** Capacity changes clamp down but never grant mana or advance any timer. */
    public boolean reconcileMaximum(int effectiveMaximum) {
        int beforeMana = mana, beforeMaximum = maximum;
        maximum = Math.max(1, effectiveMaximum);
        mana = Math.clamp(mana, 0, maximum);
        boolean changed = mana != beforeMana || maximum != beforeMaximum;
        if (changed) paymentSequence++;
        return changed;
    }

    public static final class Payment {
        private final ManaData owner;
        private final long sequence;
        private final int before, after, maximum;
        private final boolean overcast;
        private boolean consumed;
        private Payment(ManaData owner, long sequence, int before, int after, int maximum, boolean overcast) {
            this.overcast = overcast;
            this.owner = owner; this.sequence = sequence;
            this.before = before; this.after = after; this.maximum = maximum;
        }
        boolean overcast() { return overcast; }
        int before() { return before; }
        int after() { return after; }
        int paid() { return before - after; }
        boolean consume(ManaData current) {
            if (consumed) return false;
            consumed = true;
            return current == owner && current.paymentSequence == sequence
                    && current.mana == after && current.maximum == maximum && (overcast || paid() > 0);
        }
    }

    /** Returns true only when values visible in the HUD change. */
    public boolean tick(int configuredMaximum, int regeneration) {
        return tick(configuredMaximum, regeneration, 1.0);
    }

    boolean tick(int configuredMaximum, int regeneration, double regenMultiplier) {
        int previousMana = mana;
        int previousMaximum = maximum;
        boolean wasLocked = overcastLocked;
        if (regenMultiplier >= 1 || mana >= maximum) regenFraction = 0;
        reconcileMaximum(configuredMaximum);
        cooldown = Math.max(0, cooldown - 1);
        recoveryDelay = Math.max(0, recoveryDelay - 1);
        if (recoveryDelay == 0 && mana < maximum && regeneration > 0) {
            double restored = regeneration * regenMultiplier + regenFraction;
            int whole = (int) Math.floor(restored);
            mana += Math.min(whole, maximum - mana);
            regenFraction = restored - whole;
            paymentSequence++;
            recoveryDelay = 20;
        }
        if (overcastLocked && mana >= (int) Math.ceil(maximum * MagicConfig.OVERCAST_UNLOCK_RATIO.get()))
            overcastLocked = false;
        return mana != previousMana || maximum != previousMaximum || overcastLocked != wasLocked;
    }
}
