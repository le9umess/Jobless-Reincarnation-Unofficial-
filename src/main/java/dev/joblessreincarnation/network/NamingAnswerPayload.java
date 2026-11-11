package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.naming.NamingChoice;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A player's answer on the naming screen: their choice, or "later" (then the choice is ignored). The server checks it again. */
public record NamingAnswerPayload(boolean later, NamingChoice choice) implements CustomPacketPayload {
    public static final Type<NamingAnswerPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "naming_answer"));
    public static final StreamCodec<ByteBuf, NamingAnswerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, NamingAnswerPayload::later,
            NamingChoice.STREAM_CODEC, NamingAnswerPayload::choice,
            NamingAnswerPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
