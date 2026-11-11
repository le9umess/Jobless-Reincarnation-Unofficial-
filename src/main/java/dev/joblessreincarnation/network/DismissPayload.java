package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A nonce correlates input edges only. Targets, duration and payment are server decisions. */
public record DismissPayload(int nonce, int action) implements CustomPacketPayload {
    public static final int BEGIN = 0, RELEASE = 1, CANCEL = 2;
    public static final Type<DismissPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "dismiss_wall"));
    public static final StreamCodec<ByteBuf, DismissPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DismissPayload::nonce, ByteBufCodecs.VAR_INT, DismissPayload::action, DismissPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}