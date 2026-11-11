package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MoveSpellSlotPayload(ResourceLocation spell, int slot, int revision) implements CustomPacketPayload {
    public static final Type<MoveSpellSlotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "move_spell_slot"));
    public static final StreamCodec<ByteBuf, MoveSpellSlotPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, MoveSpellSlotPayload::spell,
            ByteBufCodecs.VAR_INT, MoveSpellSlotPayload::slot,
            ByteBufCodecs.VAR_INT, MoveSpellSlotPayload::revision,
            MoveSpellSlotPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
