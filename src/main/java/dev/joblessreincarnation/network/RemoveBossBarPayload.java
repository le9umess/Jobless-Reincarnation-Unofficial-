package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RemoveBossBarPayload(UUID id) implements CustomPacketPayload {
    public static final Type<RemoveBossBarPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,"remove_boss_bar"));
    public static final StreamCodec<FriendlyByteBuf,RemoveBossBarPayload> STREAM_CODEC = StreamCodec.of(
            (b,v)->b.writeUUID(v.id), b->new RemoveBossBarPayload(b.readUUID()));
    public void applyClient() { dev.joblessreincarnation.client.boss.ClientBossBars.remove(id); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
