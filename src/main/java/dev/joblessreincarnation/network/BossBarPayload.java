package dev.joblessreincarnation.network;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Server-owned full encounter view. It carries no personal mana, lessons or other private progress. */
public record BossBarPayload(UUID id, ResourceLocation dimension, Vec3 centre, boolean participant,
        Component name, Component title, ResourceLocation emblem, int phases, int phase, float progress, int[] colours,
        boolean sealable, boolean sealed, int stage, int stageTicks, int transition,
        @Nullable Component line, int lineId, int lineTicks, int arenaRadius) implements CustomPacketPayload {
    public static final Type<BossBarPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "boss_bar"));
    public BossBarPayload {
        if (phases < 1 || phases > 8 || phase < 0 || phase >= phases || stage < 0 || stage > 3
                || !Float.isFinite(progress) || progress < 0 || progress > 1 || colours.length != phases
                || !Double.isFinite(centre.x) || !Double.isFinite(centre.y) || !Double.isFinite(centre.z)
                || stageTicks < 0 || stageTicks > 1200 || lineTicks < 0 || lineTicks > 1200 || arenaRadius < 0 || arenaRadius > 32) {
            throw new IllegalArgumentException("Invalid encounter view");
        }
        colours = colours.clone();
    }
    @Override public int[] colours() { return colours.clone(); }
    public static final StreamCodec<RegistryFriendlyByteBuf, BossBarPayload> STREAM_CODEC = StreamCodec.of(
            (b, v) -> {
                b.writeUUID(v.id); b.writeResourceLocation(v.dimension);
                b.writeDouble(v.centre.x); b.writeDouble(v.centre.y); b.writeDouble(v.centre.z); b.writeBoolean(v.participant);
                ComponentSerialization.STREAM_CODEC.encode(b,v.name); ComponentSerialization.STREAM_CODEC.encode(b,v.title);
                b.writeResourceLocation(v.emblem); b.writeVarInt(v.phases); b.writeVarInt(v.phase); b.writeFloat(v.progress);
                for (int colour : v.colours) b.writeInt(colour);
                b.writeBoolean(v.sealable); b.writeBoolean(v.sealed); b.writeVarInt(v.stage); b.writeVarInt(v.stageTicks);
                b.writeVarInt(v.transition); b.writeBoolean(v.line != null);
                if (v.line != null) ComponentSerialization.STREAM_CODEC.encode(b,v.line);
                b.writeVarInt(v.lineId); b.writeVarInt(v.lineTicks); b.writeVarInt(v.arenaRadius);
            }, b -> {
                UUID id=b.readUUID(); ResourceLocation dimension=b.readResourceLocation();
                Vec3 centre=new Vec3(b.readDouble(),b.readDouble(),b.readDouble()); boolean participant=b.readBoolean();
                Component name=ComponentSerialization.STREAM_CODEC.decode(b), title=ComponentSerialization.STREAM_CODEC.decode(b);
                ResourceLocation emblem=b.readResourceLocation(); int phases=b.readVarInt(), phase=b.readVarInt(); float progress=b.readFloat();
                if (phases < 1 || phases > 8) throw new IllegalArgumentException("Invalid phase count");
                int[] colours=new int[phases]; for(int i=0;i<phases;i++)colours[i]=b.readInt();
                boolean sealable=b.readBoolean(),sealed=b.readBoolean(); int stage=b.readVarInt(),ticks=b.readVarInt(),transition=b.readVarInt();
                Component line=b.readBoolean()?ComponentSerialization.STREAM_CODEC.decode(b):null;
                return new BossBarPayload(id,dimension,centre,participant,name,title,emblem,phases,phase,progress,colours,
                        sealable,sealed,stage,ticks,transition,line,b.readVarInt(),b.readVarInt(),b.readVarInt());
            });
    /** Called only by the registered clientbound handler; the packet format itself is common-side. */
    public void applyClient() {
        dev.joblessreincarnation.client.boss.ClientBossBars.accept(new dev.joblessreincarnation.client.boss.BossBarView(
                id, ResourceKey.create(Registries.DIMENSION,dimension), centre, participant, name, title, emblem,
                phases,phase,progress,colours,sealable,sealed,
                dev.joblessreincarnation.client.boss.BossBarView.Stage.values()[stage],stageTicks,transition,line,lineId,lineTicks,arenaRadius));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
