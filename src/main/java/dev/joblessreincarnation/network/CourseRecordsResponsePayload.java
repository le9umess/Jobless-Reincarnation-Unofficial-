package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FireTutorGuidance;
import dev.joblessreincarnation.npc.TutorGuidance;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.CourseRecordSnapshot;
import dev.joblessreincarnation.training.FireTrainingTrials;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's one-shot, private response to a course-record request.
 * Values are detached snapshots of the sender's existing server state.
 */
public record CourseRecordsResponsePayload(
        int nonce,
        CourseRecordSnapshot water,
        CourseRecordSnapshot fire,
        CourseRecordSnapshot sword
) implements CustomPacketPayload {
    public static final Type<CourseRecordsResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "course_records_response"));

    public static final net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, CourseRecordsResponsePayload> STREAM_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buffer, value) -> {
                        buffer.writeVarInt(value.nonce());
                        CourseRecordSnapshot.STREAM_CODEC.encode(buffer, value.water());
                        CourseRecordSnapshot.STREAM_CODEC.encode(buffer, value.fire());
                        CourseRecordSnapshot.STREAM_CODEC.encode(buffer, value.sword());
                    },
                    buffer -> new CourseRecordsResponsePayload(buffer.readVarInt(),
                            CourseRecordSnapshot.STREAM_CODEC.decode(buffer),
                            CourseRecordSnapshot.STREAM_CODEC.decode(buffer),
                            CourseRecordSnapshot.STREAM_CODEC.decode(buffer)));

    /** Computes all three records from the server's existing read-only guidance/progress views. */
    public static CourseRecordsResponsePayload fromPlayer(int nonce, ServerPlayer player) {
        TutorGuidance.Snapshot water = TutorGuidance.snapshot(player);
        FireTutorGuidance.Snapshot fire = FireTutorGuidance.snapshot(player);
        SwordTrainingProgress swordProgress = player.hasData(ModAttachments.SWORD_TRAINING)
                ? player.getData(ModAttachments.SWORD_TRAINING) : null;
        SwordTrainingProgress.Phase swordPhase = swordProgress == null
                ? SwordTrainingProgress.Phase.NOT_STARTED : swordProgress.phase();

        return new CourseRecordsResponsePayload(nonce,
                new CourseRecordSnapshot(CourseRecordSnapshot.Course.WATER_BOLT,
                        water.stage().ordinal(), water.casts(), TrainingTrials.CAST_GOAL,
                        water.hits(), TrainingTrials.HIT_GOAL, water.charged(), water.claimed()),
                new CourseRecordSnapshot(CourseRecordSnapshot.Course.FIRE_BALL,
                        fire.stage().ordinal(), fire.casts(), FireTrainingTrials.CAST_GOAL,
                        fire.hits(), FireTrainingTrials.HIT_GOAL, false,
                        fire.stage() == FireTutorGuidance.Stage.COMPLETE),
                new CourseRecordSnapshot(CourseRecordSnapshot.Course.SWORD,
                        swordPhase.ordinal(), swordProgress == null ? 0 : swordProgress.hits(),
                        SwordTrainingProgress.GOAL, 0, 0,
                        swordProgress != null && swordProgress.recognized(),
                        swordPhase == SwordTrainingProgress.Phase.DONE));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
