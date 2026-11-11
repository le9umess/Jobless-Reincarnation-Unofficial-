package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Only cancels the sender's magic use. No private data is requested or broadcast. */
public record OpenJournalPayload() implements CustomPacketPayload {
    public static final OpenJournalPayload INSTANCE = new OpenJournalPayload();
    public static final Type<OpenJournalPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "open_journal"));
    public static final StreamCodec<ByteBuf, OpenJournalPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
