package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RecallAckPayload(int requestId) implements CustomPacketPayload {
    public static final Type<RecallAckPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "recall_ack"));
    public static final StreamCodec<ByteBuf, RecallAckPayload> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(RecallAckPayload::new, RecallAckPayload::requestId);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
