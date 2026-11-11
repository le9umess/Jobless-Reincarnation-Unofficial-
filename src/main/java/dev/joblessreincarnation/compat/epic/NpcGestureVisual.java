package dev.joblessreincarnation.compat.epic;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/** Public presentation only. No target/player/progress data, NBT codec or copy-on-death. */
public final class NpcGestureVisual {
    public enum Kind { NONE, GREETING, LECTURE, SWORD_DEMO, ATTEMPT }
    public static final int ENTRY_TICKS = 4;
    private final Entity owner;
    private Kind kind=Kind.NONE;
    private long sequence;
    private long startedAt;
    private int duration;
    private boolean received;
    private int receivedAt,receivedAge;
    public NpcGestureVisual(Entity owner) { this.owner=owner; }
    public Kind kind() { return kind; }
    public long sequence() { return sequence; }
    public int duration() { return duration; }
    public double age(double partialTick) {
        return received ? (double)owner.tickCount-receivedAt+receivedAge+partialTick
                : (double)owner.level().getGameTime()-startedAt+partialTick;
    }
    public boolean live() {
        double age=age(0);
        return kind!=Kind.NONE && owner.isAlive() && !owner.isRemoved() && age>=0 && age<duration;
    }
    public void begin(Kind value,int clipTicks) {
        if(value==null || value==Kind.NONE || clipTicks<1 || clipTicks>100)throw new IllegalArgumentException("Invalid visual lifetime");
        kind=value;sequence=sequence==Long.MAX_VALUE?1:sequence+1;
        startedAt=owner.level().getGameTime();duration=ENTRY_TICKS+clipTicks;received=false;
    }
    public void clear() { kind=Kind.NONE;duration=0;sequence=sequence==Long.MAX_VALUE?1:sequence+1; }
    public static final class Sync implements AttachmentSyncHandler<NpcGestureVisual> {
        @Override public void write(RegistryFriendlyByteBuf b,NpcGestureVisual v,boolean initial) {
            boolean active=v.live();
            b.writeByte(active?v.kind.ordinal():0);b.writeVarLong(v.sequence);
            b.writeVarInt(active?(int)v.age(0):0);b.writeVarInt(active?v.duration:0);
        }
        @Override public NpcGestureVisual read(IAttachmentHolder holder,RegistryFriendlyByteBuf b,NpcGestureVisual previous) {
            int id=b.readUnsignedByte();long seq=b.readVarLong();int age=b.readVarInt(),duration=b.readVarInt();
            var v=new NpcGestureVisual((Entity)holder);
            v.sequence=Math.max(0,seq);v.received=true;v.receivedAt=v.owner.tickCount;
            if(id>0&&id<Kind.values().length&&age>=0&&duration>0&&duration<=104&&age<duration) {
                v.kind=Kind.values()[id];v.receivedAge=age;v.duration=duration;
            }
            return v;
        }
    }
}
