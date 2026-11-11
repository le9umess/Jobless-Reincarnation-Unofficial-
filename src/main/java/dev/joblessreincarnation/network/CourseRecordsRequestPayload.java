package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests one read-only snapshot for the connected player. */
public record CourseRecordsRequestPayload(int nonce) implements CustomPacketPayload {
    public static final Type<CourseRecordsRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "course_records_request"));
    public static final StreamCodec<FriendlyByteBuf, CourseRecordsRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CourseRecordsRequestPayload::nonce, CourseRecordsRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
