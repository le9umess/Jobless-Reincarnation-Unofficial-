package dev.joblessreincarnation.training;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * A small, detached view of one existing lesson.  This is a transport value,
 * not another attachment or a second copy of course progress.
 *
 * <p>The meaning of the two counters is fixed by {@link Course}: water and
 * fire use casts/hits, while sword uses hits/unused.  The server supplies the
 * stage and goals; clients only render the values.</p>
 */
public record CourseRecordSnapshot(
        Course course,
        int stage,
        int primary,
        int primaryTarget,
        int secondary,
        int secondaryTarget,
        boolean flag,
        boolean completed
) {
    private static final int MAX_STAGE = 15;
    private static final int MAX_COUNT = 1000;

    public enum Course { WATER_BOLT, FIRE_BALL, SWORD }

    public CourseRecordSnapshot {
        if (course == null) throw new IllegalArgumentException("course is required");
        stage = Math.clamp(stage, 0, MAX_STAGE);
        primaryTarget = Math.clamp(primaryTarget, 0, MAX_COUNT);
        secondaryTarget = Math.clamp(secondaryTarget, 0, MAX_COUNT);
        primary = Math.clamp(primary, 0, primaryTarget);
        secondary = Math.clamp(secondary, 0, secondaryTarget);
    }

    /** Compact, bounded network representation; no strings or player identity leave the server. */
    public static final StreamCodec<FriendlyByteBuf, CourseRecordSnapshot> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.course().ordinal());
                buffer.writeVarInt(value.stage());
                buffer.writeVarInt(value.primary());
                buffer.writeVarInt(value.primaryTarget());
                buffer.writeVarInt(value.secondary());
                buffer.writeVarInt(value.secondaryTarget());
                buffer.writeBoolean(value.flag());
                buffer.writeBoolean(value.completed());
            },
            buffer -> {
                int course = buffer.readVarInt();
                if (course < 0 || course >= Course.values().length) {
                    throw new IllegalArgumentException("Unknown course record: " + course);
                }
                return new CourseRecordSnapshot(Course.values()[course], buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
            });
}
