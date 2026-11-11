package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requested ID only; learned status and all mutation remain server decisions. */
public record SelectSpellPayload(ResourceLocation spell, int revision) implements CustomPacketPayload {
    public static final Type<SelectSpellPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "select_spell"));
    public static final StreamCodec<ByteBuf, SelectSpellPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, SelectSpellPayload::spell,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, SelectSpellPayload::revision, SelectSpellPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
