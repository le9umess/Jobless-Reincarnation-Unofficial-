package dev.joblessreincarnation.registry;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.ManaGrowthData;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.AuraVisual;
import dev.joblessreincarnation.training.SchoolCombatState;
import dev.joblessreincarnation.training.BattleAura;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.network.OwnerOnlyAttachmentSync;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, JoblessReincarnation.MOD_ID);

    public static final Supplier<AttachmentType<ManaData>> MANA = TYPES.register("mana", () ->
            AttachmentType.builder(() -> ManaData.full(MagicConfig.MAX_MANA.getAsInt()))
                    .serialize(ManaData.CODEC)
                    .sync(OwnerOnlyAttachmentSync.of(ManaData.STREAM_CODEC))
                    .build());

    public static final Supplier<AttachmentType<dev.joblessreincarnation.magic.SearingEffect.Burn>> SEARING =
            TYPES.register("searing_source", () -> AttachmentType.builder(() -> dev.joblessreincarnation.magic.SearingEffect.Burn.NONE)
                    .serialize(dev.joblessreincarnation.magic.SearingEffect.Burn.CODEC).build());

    // Growth records are private server data; only the effective capacity uses MANA owner synchronization.
    public static final Supplier<AttachmentType<ManaGrowthData>> MANA_GROWTH = TYPES.register("mana_growth", () ->
            AttachmentType.builder(ManaGrowthData::new)
                    .serialize(ManaGrowthData.CODEC)
                    .copyOnDeath()
                    .build());

    public static final Supplier<AttachmentType<MagicKnowledge>> KNOWLEDGE = TYPES.register("magic_knowledge", () ->
            AttachmentType.builder(MagicKnowledge::new)
                    .serialize(MagicKnowledge.CODEC)
                    .copyOnDeath()
                    .sync(OwnerOnlyAttachmentSync.of(MagicKnowledge.STREAM_CODEC))
                    .build());

    public static final Supplier<AttachmentType<CastingData>> CASTING = TYPES.register("casting", () ->
            AttachmentType.builder(CastingData::new)
                    .serialize(CastingData.CODEC)
                    .copyOnDeath()
                    .sync(OwnerOnlyAttachmentSync.of(CastingData.STREAM_CODEC))
                    .build());

    // What the family calls this player (N01). Only their own client gets it: to ask on first entry, for previews and the
    // plant dictionary. Before the server's record arrives a client assumes "nothing to ask", so a known player is never asked.
    public static final Supplier<AttachmentType<dev.joblessreincarnation.naming.PlayerNaming>> NAMING = TYPES.register("naming", () ->
            AttachmentType.builder(holder -> holder instanceof net.minecraft.world.entity.player.Player player && player.level().isClientSide
                            ? dev.joblessreincarnation.naming.PlayerNaming.UNKNOWN : dev.joblessreincarnation.naming.PlayerNaming.NEW)
                    .serialize(dev.joblessreincarnation.naming.PlayerNaming.CODEC)
                    .copyOnDeath()
                    .sync(OwnerOnlyAttachmentSync.of(dev.joblessreincarnation.naming.PlayerNaming.STREAM_CODEC))
                    .build());

    // Personal milestones stay on the server; an open board sends a small menu snapshot.
    public static final Supplier<AttachmentType<TrainingProgress>> TRAINING = TYPES.register("training_progress", () ->
            AttachmentType.builder(TrainingProgress::new)
                    .serialize(TrainingProgress.CODEC)
                    .copyOnDeath()
                    .build());

    /** Private course data; only server replies expose the asking player's own progress. */
    public static final Supplier<AttachmentType<SwordTrainingProgress>> SWORD_TRAINING = TYPES.register("sword_training", () ->
            AttachmentType.builder(SwordTrainingProgress::new)
                    .serialize(SwordTrainingProgress.CODEC)
                    .copyOnDeath()
                    .build());

    public static final Supplier<AttachmentType<dev.joblessreincarnation.training.LightBladeState>> LIGHT_BLADE_SESSION = TYPES.register("light_blade_session", () ->
            AttachmentType.builder(dev.joblessreincarnation.training.LightBladeState::new).build());

    public static final Supplier<AttachmentType<SchoolCombatState>> SCHOOL_COMBAT = TYPES.register("school_combat", () ->
            AttachmentType.builder(SchoolCombatState::new).build());
    public static final Supplier<AttachmentType<Long>> DUST_IN_EYES_UNTIL = TYPES.register("dust_in_eyes_until", () ->
            AttachmentType.builder(() -> 0L).build());

    // Held-technique ownership only; never serialized, copied on death or synchronized.
    public static final Supplier<AttachmentType<BattleAura.Gathering>> AURA_GATHERING = TYPES.register("aura_gathering", () ->
            AttachmentType.builder(() -> BattleAura.Gathering.NONE).build());

    /** Public visual phase and timing only. The sword lesson and mana are private. */
    public static final Supplier<AttachmentType<AuraVisual>> BATTLE_AURA_VISUAL = TYPES.register("battle_aura_visual", () ->
            AttachmentType.builder(() -> AuraVisual.OFF).sync(AuraVisual.STREAM_CODEC).build());

    // Public visual state only: 0=inactive, paired entry/hold values per spell (water remains 1/2).
    // Keep the V01 attachment ID; never persisted or copied on death.
    public static final Supplier<AttachmentType<Integer>> EPIC_WATER_PHASE = TYPES.register("epic_water_phase", () ->
            AttachmentType.builder(() -> 0).sync(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());

    // NPC gestures expose only the bounded public visual phase, never the talking player's data.
    public static final Supplier<AttachmentType<dev.joblessreincarnation.compat.epic.NpcGestureVisual>> NPC_GESTURE_VISUAL = TYPES.register("npc_gesture_visual", () ->
            AttachmentType.builder(holder -> new dev.joblessreincarnation.compat.epic.NpcGestureVisual((net.minecraft.world.entity.Entity) holder))
                    .sync(new dev.joblessreincarnation.compat.epic.NpcGestureVisual.Sync()).build());

    public static final Supplier<AttachmentType<dev.joblessreincarnation.encounter.PaulDuelProgress>> PAUL_DUEL_PROGRESS = TYPES.register("paul_duel_progress", () ->
            AttachmentType.builder(() -> new dev.joblessreincarnation.encounter.PaulDuelProgress(false))
                    .serialize(dev.joblessreincarnation.encounter.PaulDuelProgress.CODEC).copyOnDeath().build());

    /** Server-side world-generation receipts; stored with a protochunk's blocks and entities. */
    public static final Supplier<AttachmentType<net.minecraft.nbt.CompoundTag>> BUENA_CHUNK = TYPES.register("buena_generation", () ->
            AttachmentType.builder(() -> new net.minecraft.nbt.CompoundTag()).serialize(net.minecraft.nbt.CompoundTag.CODEC).build());

    private ModAttachments() {}
}
