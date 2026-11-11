package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RecallSpellPayload(int slot, ResourceLocation spell, int revision, int requestId) implements CustomPacketPayload {
    public static final Type<RecallSpellPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "recall_spell"));
    public static final StreamCodec<ByteBuf, RecallSpellPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecallSpellPayload::slot,
            ResourceLocation.STREAM_CODEC, RecallSpellPayload::spell,
            ByteBufCodecs.VAR_INT, RecallSpellPayload::revision,
            ByteBufCodecs.VAR_INT, RecallSpellPayload::requestId,
            RecallSpellPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
